/* Copyright 2014-2018, hbz. Licensed under the Eclipse Public License 1.0 */

package controllers;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static play.test.Helpers.GET;
import static play.test.Helpers.POST;
import static play.test.Helpers.contentAsString;
import static play.test.Helpers.fakeApplication;
import static play.test.Helpers.fakeRequest;
import static play.test.Helpers.route;
import static play.test.Helpers.running;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import modules.IndexTest;
import play.Application;
import play.Logger;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@SuppressWarnings("javadoc")
public class ReconcileTest extends IndexTest {

	@Test
	public void reconcileMetadataRequestNoCallback() {
		metadataRequest("/gnd/reconcile");
	}

	@Test
	public void reconcileMetadataRequestNoCallbackTrailingSlash() {
		metadataRequest("/gnd/reconcile/");
	}

	private void metadataRequest(String uri) {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = route(application, fakeRequest(GET, uri));
			assertNotNull(result);
			assertThat(result.contentType().get(), is(equalTo("application/json")));
			assertNotNull(Json.parse(contentAsString(result)));
			assertThat(contentAsString(result), containsString("https:"));
			assertThat(contentAsString(result), not(containsString("http:")));
			assertThat(result.header("Access-Control-Allow-Origin").get(), is(equalTo("*")));
		});
	}

	@Test
	public void reconcilePropertiesRequest() {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = route(application, fakeRequest(GET, "/gnd/reconcile/properties"));
			assertNotNull(result);
			assertThat(result.contentType().get(), is(equalTo("application/json")));
			assertThat(result.header("Access-Control-Allow-Origin").get(), is(equalTo("*")));
		});
	}

	@Test
	public void reconcileMetadataRequestWithCallback() {
		metadataRequestWithCallback("/gnd/reconcile?callback=jsonp");
	}

	@Test
	public void reconcileMetadataRequestWithCallbackTrailingSlash() {
		metadataRequestWithCallback("/gnd/reconcile/?callback=jsonp");
	}

	private void metadataRequestWithCallback(String uri) {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = route(application, fakeRequest(GET, uri));
			assertNotNull(result);
			assertThat(result.contentType().get(), is(equalTo("application/json")));
			assertThat(contentAsString(result), startsWith("/**/jsonp("));
		});
	}

	@Test
	// curl --data 'queries={"q99":{"query":"*"}}' localhost:9000/gnd/reconcile
	public void reconcileRequest() {
		reconcileRequest("/gnd/reconcile");
	}

	@Test
	// curl --data 'queries={"q99":{"query":"*"}}' localhost:9000/gnd/reconcile/
	public void reconcileRequestTrailingSlash() {
		reconcileRequest("/gnd/reconcile/");
	}

	private void reconcileRequest(String uri) {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = route(application, fakeRequest(POST, uri)
					.bodyForm(ImmutableMap.of("queries", "{\"q99\":{\"query\":\"Twain, Mark\"}}")));
			String content = contentAsString(result);
			Logger.debug(Json.prettyPrint(Json.parse(content)));
			assertThat(content, containsString("q99"));
			assertThat(content, containsString("\"match\":false"));
			assertThat(content, containsString("\"match\":true"));
			assertThat(result.header("Access-Control-Allow-Origin").get(), is(equalTo("*")));
		});
	}

	@Test
	// curl -g 'localhost:9000/gnd/reconcile?queries={"q99":{"query":"*"}}'
	public void reconcileRequestGet() {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = null;
			try {
				result = route(application,
						fakeRequest(GET,
								"/gnd/reconcile?queries=" + URLEncoder.encode(
										"{\"q99\":{\"query\":\"Conference +-=<>(){}[]^ (1997 : Kyoto : Japan)\"}}",
										StandardCharsets.UTF_8.name())));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
			assertNotNull(result);
			assertThat(result.status(), is(equalTo(Http.Status.OK)));
			String content = contentAsString(result);
			Logger.debug(Json.prettyPrint(Json.parse(content)));
			assertThat(content, containsString("16269284-5"));
		});
	}

	@Test
	// curl --data 'queries={"q99":{"query":"*"}}' localhost:9000/gnd/reconcile
	public void reconcileRequestReservedChars() {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = route(application, fakeRequest(POST, "/gnd/reconcile")//
					.bodyForm(ImmutableMap.of("queries",
							"{\"q99\":{\"query\":\"Conference +-=<>(){}[]^ (1997 : Kyoto : Japan)\"}}")));
			assertThat(result.status(), is(equalTo(Http.Status.OK)));
			String content = contentAsString(result);
			Logger.debug(Json.prettyPrint(Json.parse(content)));
			assertThat(content, containsString("16269284-5"));
		});
	}

	@Test
	public void reconcileRequestWithType() {
		Application application = fakeApplication();
		running(application, () -> {
			Result result = route(application, fakeRequest(POST, "/gnd/reconcile").bodyForm(
					ImmutableMap.of("queries", "{\"q99\":{\"query\":\"Twain, Mark\", \"type\":\"CorporateBody\"}}")));
			String content = contentAsString(result);
			Logger.debug(Json.prettyPrint(Json.parse(content)));
			assertThat(content, containsString("q99"));
			assertFalse(Json.parse(content).findValue("result").elements().hasNext());
		});
	}

}