package modules;

import static controllers.HomeController.CONFIG;
import static controllers.HomeController.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.BoostingQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import controllers.HomeController;
import play.Logger;
import play.inject.ApplicationLifecycle;

public interface IndexComponent {
	Client client();

	SearchResponse query(String q, String type, int from, int size);

	public default SearchResponse query(String q) {
		return query(q, "", 0, 10);
	}
}

class ElasticsearchServer implements IndexComponent {

	private static final Settings SETTINGS = Settings.builder()
			.put("cluster.name", HomeController.config("index.cluster")).build();

	private final TransportClient client;

	@Inject
	public ElasticsearchServer(ApplicationLifecycle lifecycle) {
		client = new PreBuiltTransportClient(SETTINGS);
		CONFIG.getStringList("index.hosts").forEach((host) -> {
			try {
				client.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), 9300));
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		});
		startup();
		lifecycle.addStopHook(() -> {
			client.close();
			return null;
		});
	}

	@Override
	public Client client() {
		return client;
	}

	private void startup() {
		client.admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet();
		client.admin().indices().refresh(new RefreshRequest()).actionGet();
		String pathToJson = config("data.jsonlines");
		String pathToUpdates = config("data.updates.data");
		String indexName = config("index.name");
		try {
			if (!indexExists(client, indexName)) {
				createEmptyIndex(client, indexName, config("index.settings"));
				if (new File(pathToJson).exists()) {
					indexData(client, pathToJson, indexName);
				}
			} else {
				Logger.info("Index {} exists. Delete index or change index name in config to reindex from {}",
						indexName, pathToJson);
			}
			if (new File(pathToUpdates).exists()) {
				Logger.info("Indexing updates from " + pathToUpdates);
				indexData(client, pathToUpdates, indexName);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		Logger.info("Using Elasticsearch index settings: {}", SETTINGS.getAsMap());
	}

	static void deleteIndex(final Client client, final String index) {
		client.admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet();
		if (indexExists(client, index)) {
			client.admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
		}
	}

	private static boolean indexExists(final Client client, final String index) {
		return client.admin().indices().prepareExists(index).execute().actionGet().isExists();
	}

	static void createEmptyIndex(final Client aClient, final String aIndexName, final String aPathToIndexSettings)
			throws IOException {
		deleteIndex(aClient, aIndexName);
		CreateIndexRequestBuilder cirb = aClient.admin().indices().prepareCreate(aIndexName);
		if (aPathToIndexSettings != null) {
			String settingsMappings = Files.lines(Paths.get(aPathToIndexSettings)).collect(Collectors.joining());
			cirb.setSource(settingsMappings, XContentType.JSON);
		}
		cirb.execute().actionGet();//
		aClient.admin().indices().refresh(new RefreshRequest()).actionGet();
	}

	static BulkRequestBuilder bulkRequest = null;

	static void indexData(final Client aClient, final String aPath, final String aIndex) throws IOException {
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new FileInputStream(aPath), StandardCharsets.UTF_8))) {
			readData(br, aClient, aIndex);
		}
		aClient.admin().indices().refresh(new RefreshRequest()).actionGet();
	}

	private static void readData(final BufferedReader br, final Client client, final String aIndex) throws IOException {
		final ObjectMapper mapper = new ObjectMapper();
		String line;
		int currentLine = 1;
		String data = null;
		String[] idUriParts = null;
		String id = null;

		bulkRequest = client.prepareBulk();
		int pendingIndexRequests = 0;

		// First line: index with id, second line: source
		while ((line = br.readLine()) != null) {
			JsonNode rootNode = mapper.readValue(line, JsonNode.class);
			if (currentLine % 2 != 0) {
				JsonNode index = rootNode.get("index");
				idUriParts = index.findValue("_id").asText().split("/");
				id = idUriParts[idUriParts.length - 1].replace("#!", "");
				pendingIndexRequests++;
			} else {
				Form nfc = Normalizer.Form.NFC;
				data = Normalizer.isNormalized(line, nfc) ? line : Normalizer.normalize(line, nfc);
				bulkRequest
						.add(client.prepareIndex(aIndex, config("index.type"), id).setSource(data, XContentType.JSON));
			}
			currentLine++;
			if (pendingIndexRequests == 1000) {
				executeBulk(pendingIndexRequests);
				bulkRequest = client.prepareBulk();
				pendingIndexRequests = 0;
			}
		}
		executeBulk(pendingIndexRequests);
	}

	private static void executeBulk(int pendingIndexRequests) {
		if (pendingIndexRequests == 0) {
			return;
		}
		BulkResponse bulkResponse = bulkRequest.execute().actionGet();
		if (bulkResponse.hasFailures()) {
			bulkResponse.forEach(item -> {
				if (item.isFailed()) {
					Logger.error("Indexing {} failed: {}", item.getId(), item.getFailureMessage());
				}
			});
		}
		Logger.info("Indexed {} docs, took: {}", pendingIndexRequests, bulkResponse.getTook());
	}

	@Override
	public SearchResponse query(String q, String filter, int from, int size) {
		QueryStringQueryBuilder positive = QueryBuilders.queryStringQuery(q).field("_all").field("preferredName", 2f)
				.field("variantName", 1f).field("gndIdentifier", 2f);
		MatchQueryBuilder negative = QueryBuilders.matchQuery("type", "UndifferentiatedPerson");
		BoostingQueryBuilder boostQuery = QueryBuilders.boostingQuery(positive, negative).negativeBoost(0.1f);
		BoolQueryBuilder query = QueryBuilders.boolQuery().must(boostQuery);
		if (!filter.isEmpty()) {
			query = query.filter(QueryBuilders.queryStringQuery(filter));
		}
		SearchRequestBuilder requestBuilder = client().prepareSearch(config("index.name")).setQuery(query).setFrom(from)
				.setSize(size);
		for (String a : HomeController.AGGREGATIONS) {
			requestBuilder.addAggregation(AggregationBuilders.terms(a).field(a).size(1000));
		}
		SearchResponse response = requestBuilder.get();
		return response;
	}
}