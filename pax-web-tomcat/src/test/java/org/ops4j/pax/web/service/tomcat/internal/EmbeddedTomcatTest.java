/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.tomcat.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.GenericFilter;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Executor;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.WebResourceSet;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.core.StandardThreadExecutor;
import org.apache.catalina.core.StandardWrapper;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.digester.Digester;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EmbeddedTomcatTest {

	public static final Logger LOG = LoggerFactory.getLogger(EmbeddedTomcatTest.class);

	@BeforeClass
	public static void initClass() {
		SLF4JBridgeHandler.removeHandlersForRootLogger();
		SLF4JBridgeHandler.install();
	}

	@AfterClass
	public static void cleanupClass() {
		SLF4JBridgeHandler.uninstall();
	}

	@Test
	public void useBootstrap() throws Exception {
		// org.apache.catalina.startup.Bootstrap is the class used by normal Tomcat installation
		// it loads org.apache.catalina.startup.Catalina class, sets classloader inside and calls various methods
		// by reflection (due to classloader isolation)
		//
		// with `bin/catalina.sh start` command, the called methods are:
		// - org.apache.catalina.startup.Catalina.load():
		//    - commons-digester parses server.xml and calls different methods on object tree with Catalina as root
		//    - digester is configured explicitly in Java code in
		//      org.apache.catalina.startup.Catalina.createStartDigester()
		//    - org.apache.catalina.startup.Catalina.server <- org.apache.catalina.core.StandardServer
		// - org.apache.catalina.startup.Catalina.start()
		//    - org.apache.catalina.core.StandardServer.startInternal()

		// even if in theory Service could be running without server, it is required by different components, e.g.,
		// connectors that want to access utility thread pool
		Server server = new StandardServer();
		server.setCatalinaBase(new File("target"));

		Service service = new StandardService();
		service.setName("Catalina");
		server.addService(service);

		Executor executor = new StandardThreadExecutor();
		service.addExecutor(executor);

		Connector connector = new Connector("HTTP/1.1");
		connector.setPort(0);
		connector.setProperty("address", "127.0.0.1");
		service.addConnector(connector);

		Engine engine = new StandardEngine();
		engine.setName("Catalina");
		engine.setDefaultHost("localhost");
		service.setContainer(engine);

		Host host = new StandardHost();
		host.setName("localhost");
		host.setAppBase(".");
		engine.addChild(host);

		// org.apache.catalina.util.ContextName.ContextName(java.lang.String, boolean) explicitly
		// changes "ROOT" name into
		// org.apache.catalina.util.ContextName.path == org.apache.catalina.util.ContextName.name == ""
		Context context = new StandardContext();
		context.setName("");
		context.setPath("");
		// Fix startup sequence - required if you don't use web.xml. The start() method in context will set
		// 'configured' to false - and expects a listener to set it back to true.
//		context.addLifecycleListener(new Tomcat.FixContextListener());
		context.addLifecycleListener((event) -> {
			if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
				context.setConfigured(true);
			}
		});
		host.addChild(context);

		Servlet servlet = new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				LOG.info("Handling request: {}", req.toString());
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK\n");
				resp.getWriter().close();
			}
		};

		Wrapper wrapper = new StandardWrapper();
		wrapper.setServlet(servlet);
		wrapper.setName("s1");
		context.addChild(wrapper);
		context.addServletMappingDecoded("/", wrapper.getName(), false);

		server.start();

		LOG.info("Local port after start: {}", connector.getLocalPort());

		String response = send(connector.getLocalPort(), "/");
		assertTrue(response.endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.destroy();
	}

	@Test
	public void embeddedServerWithServletContextHandlerAndOnlyFilter() throws Exception {
		Server server = new StandardServer();
		server.setCatalinaBase(new File("target"));

		Service service = new StandardService();
		service.setName("Catalina");
		server.addService(service);

		Executor executor = new StandardThreadExecutor();
		service.addExecutor(executor);

		Connector connector = new Connector("HTTP/1.1");
		connector.setPort(0);
		service.addConnector(connector);

		Engine engine = new StandardEngine();
		engine.setName("Catalina");
		engine.setDefaultHost("localhost");
		service.setContainer(engine);

		Host host = new StandardHost();
		host.setName("localhost");
		host.setAppBase(".");
		engine.addChild(host);

		Context rootContext = new StandardContext();
		rootContext.setName("");
		rootContext.setPath("");
		rootContext.setMapperContextRootRedirectEnabled(false);
		rootContext.addLifecycleListener((event) -> {
			if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
				rootContext.setConfigured(true);
			}
		});
		host.addChild(rootContext);

		FilterDef def = new FilterDef();
		FilterMap map = new FilterMap();
		def.setFilterName("f1");
		def.setFilter(new Filter() {
			@Override
			public void init(FilterConfig filterConfig) throws ServletException {
			}

			@Override
			public void doFilter(ServletRequest request, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK");
				resp.getWriter().close();
			}
		});
		map.setFilterName("f1");
		map.addURLPattern("/*");
		map.setDispatcher(DispatcherType.REQUEST.name());
		rootContext.addFilterDef(def);
		rootContext.addFilterMap(map);

		// filter-only pipeline works in Tomcat only if there's at least "default" servlet.
		StandardWrapper defaultWrapper = new StandardWrapper();
		defaultWrapper.setName("default");
		defaultWrapper.setServletClass(DefaultServlet.class.getName());
		rootContext.addChild(defaultWrapper);
		rootContext.addServletMappingDecoded("/", "default");

		server.start();

		int port = connector.getLocalPort();
		String response = send(connector.getLocalPort(), "/anything");
		assertTrue(response.endsWith("OK"));

		server.stop();
		server.destroy();
	}

	@Test
	public void embeddedServerWithTomcatResourceServlet() throws Exception {
		Server server = new StandardServer();
		server.setCatalinaBase(new File("target"));

		Service service = new StandardService();
		service.setName("Catalina");
		server.addService(service);

		Executor executor = new StandardThreadExecutor();
		service.addExecutor(executor);

		Connector connector = new Connector("HTTP/1.1");
		connector.setPort(0);
		service.addConnector(connector);

		Engine engine = new StandardEngine();
		engine.setName("Catalina");
		engine.setDefaultHost("localhost");
		service.setContainer(engine);

		Host host = new StandardHost();
		host.setName("localhost");
		host.setAppBase(".");
		engine.addChild(host);

		Context rootContext = new StandardContext();
		rootContext.setName("");
		rootContext.setPath("");
//		rootContext.setDocBase(new File("target").getAbsolutePath());
		StandardRoot standardRoot = new StandardRoot(rootContext) {
			@Override
			protected WebResourceSet createMainResourceSet() {
				return new DirResourceSet(this, "/", new File("target").getAbsolutePath(), "/");
			}
		};
		// setting resources here will make it available for DefaultServlet's which checks
		// org.apache.catalina.Globals.RESOURCES_ATTR context attribute.
		// if we want to have more "roots" (for more OSGi http "resources") we have to be clever about
		// DefaultServlet customization
		standardRoot.setCachingAllowed(true);
//		standardRoot.setCacheMaxSize(-1L);
//		standardRoot.setCacheObjectMaxSize(-1);
//		standardRoot.setCacheTtl(-1L);
		rootContext.setResources(standardRoot);
		rootContext.setMapperContextRootRedirectEnabled(false);
		rootContext.addLifecycleListener((event) -> {
			if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
				rootContext.setConfigured(true);
			}
		});
		host.addChild(rootContext);

		// filter-only pipeline works in Tomcat only if there's at least "default" servlet.
		StandardWrapper defaultWrapper = new StandardWrapper();
		defaultWrapper.setName("default");
		defaultWrapper.setServletClass(DefaultServlet.class.getName());
		defaultWrapper.addInitParameter("listings", "false");

		rootContext.addChild(defaultWrapper);
		rootContext.addServletMappingDecoded("/", "default");

		server.start();

		int port = connector.getLocalPort();

		// Tomcat generates ETag using org.apache.catalina.webresources.AbstractResource.getETag()
		// which is 'W/"' + length + '-' + lastModified + '"'

		String response = send(port, "/test-classes/log4j2-test.properties");
		Map<String, String> headers = extractHeaders(response);
		assertTrue(response.contains("ETag: W/"));
		assertTrue(response.contains("rootLogger.appenderRef.stdout.ref = stdout"));

		response = send(port, "/test-classes/log4j2-test.properties",
				"If-None-Match: " + headers.get("ETag"),
				"If-Modified-Since: " + headers.get("Date"));
		assertTrue(response.contains("HTTP/1.1 304"));
		assertFalse(response.contains("rootLogger.appenderRef.stdout.ref = stdout"));

		server.stop();
		server.destroy();
	}

	@Test
	public void addContextAfterServerHasStarted() throws Exception {
		Server server = new StandardServer();
		server.setCatalinaBase(new File("target"));

		Service service = new StandardService();
		service.setName("Catalina");
		server.addService(service);

		Executor executor = new StandardThreadExecutor();
		service.addExecutor(executor);

		Connector connector = new Connector("HTTP/1.1");
		connector.setPort(0);
		service.addConnector(connector);

		Engine engine = new StandardEngine();
		engine.setName("Catalina");
		engine.setDefaultHost("localhost");
		service.setContainer(engine);

		Host host = new StandardHost();
		host.setName("localhost");
		host.setAppBase(".");
		engine.addChild(host);

		Servlet servlet = new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				LOG.info("Handling request: {}", req.toString());
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");

				String response = String.format("| %s | %s | %s |", req.getContextPath(), req.getServletPath(), req.getPathInfo());
				resp.getWriter().write(response);
				resp.getWriter().close();
			}
		};

		Context c1 = new StandardContext();
		c1.setName("c1");
		// org.apache.catalina.core.StandardContext.setPath()
		//  - "/" or null - warning, conversion to ""
		//  - "" or "/<path>" - ok
		//  - if path ends with "/", warning, slash is trimmed
		c1.setPath("/c1");
		c1.setMapperContextRootRedirectEnabled(false);
		c1.addLifecycleListener((event) -> {
			if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
				c1.setConfigured(true);
			}
		});
		host.addChild(c1);

		Wrapper wrapper1 = new StandardWrapper();
		wrapper1.setServlet(servlet);
		wrapper1.setName("s1");

		c1.addChild(wrapper1);
		c1.addServletMappingDecoded("/s1", wrapper1.getName(), false);

		server.start();

		LOG.info("Local port after start: {}", connector.getLocalPort());

		String response;

		response = send(connector.getLocalPort(), "/c1/s1");
		assertTrue(response.endsWith("| /c1 | /s1 | null |"));

		response = send(connector.getLocalPort(), "/c1/s2");
		assertTrue(response.contains("HTTP/1.1 404"));

		response = send(connector.getLocalPort(), "/c2/s1");
		assertTrue(response.contains("HTTP/1.1 404"));

		// add new context

		Context c2 = new StandardContext();
		c2.setName("c2");
		c2.setPath("/c2");
		c2.setMapperContextRootRedirectEnabled(false);
		c2.addLifecycleListener((event) -> {
			if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
				c2.setConfigured(true);
			}
		});
		host.addChild(c2);

		Wrapper wrapper2 = new StandardWrapper();
		wrapper2.setServlet(servlet);
		wrapper2.setName("s1");

		c2.addChild(wrapper2);
		c2.addServletMappingDecoded("/s1", wrapper2.getName(), false);

		// add new servlet to existing context

		Wrapper wrapper3 = new StandardWrapper();
		wrapper3.setServlet(servlet);
		wrapper3.setName("s2");

		c1.addChild(wrapper3);
		c1.addServletMappingDecoded("/s2", wrapper3.getName(), false);

		response = send(connector.getLocalPort(), "/c1/s1");
		assertTrue(response.endsWith("| /c1 | /s1 | null |"));

		response = send(connector.getLocalPort(), "/c1/s2");
		assertTrue(response.endsWith("| /c1 | /s2 | null |"));

		response = send(connector.getLocalPort(), "/c2/s1");
		assertTrue(response.endsWith("| /c2 | /s1 | null |"));

		server.stop();
		server.destroy();
	}

	@Test
	public void useTomcat() throws Exception {
		// org.apache.catalina.startup.Tomcat class is designed for embedded usage, it doesn't require parsing
		// server.xml file and instead exposes lots of addXXX() methods.
		// it's used similarly to Catalina:
		// - org.apache.catalina.startup.Tomcat.init() which calls org.apache.catalina.startup.Catalina.load()
		// - org.apache.catalina.startup.Tomcat.start() which calls org.apache.catalina.core.StandardServer.startInternal()

		Tomcat tomcat = new Tomcat();
		tomcat.setPort(0);

		tomcat.setBaseDir("target");

		// this method adds missing nested objects: Catalina -> Server -> Service -> Engine -> Host -> Context
		Context ctx = tomcat.addContext("", ".");

		Servlet servlet = new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				LOG.info("Handling request: {}", req.toString());
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK\n");
				resp.getWriter().close();
			}
		};

		// adding a servlet can be done through Tomcat instance
		//tomcat.addServlet(...)
		Wrapper wrapper = new Tomcat.ExistingStandardWrapper(servlet);
		wrapper.setName("s1");
		ctx.addChild(wrapper);

		// adding a mapping
		ctx.addServletMappingDecoded("/", "s1");

		// by default, org.apache.catalina.core.StandardHost.appBase is "webapps"
		tomcat.getHost().setAppBase(".");

		tomcat.start();

		LOG.info("Local port after start: {}", tomcat.getConnector().getLocalPort());

		String response = send(tomcat.getConnector().getLocalPort(), "/");
		assertTrue(response.endsWith("\r\n\r\nOK\n"));

		tomcat.stop();
		tomcat.destroy();
	}

	@Test
	public void embeddedServerWithExternalConfiguration() throws Exception {
		File webXml = new File("target/WEB-INF/web.xml");
		webXml.getParentFile().mkdirs();
		webXml.delete();

		try (FileWriter writer = new FileWriter(webXml)) {
			writer.write("<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
					"    xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd\"\n" +
					"    version=\"4.0\">\n" +
					"\n" +
					"    <servlet>\n" +
					"        <servlet-name>test-servlet</servlet-name>\n" +
					"        <servlet-class>org.ops4j.pax.web.service.tomcat.internal.EmbeddedTomcatTest$TestServlet</servlet-class>\n" +
					"    </servlet>\n" +
					"\n" +
					"    <servlet-mapping>\n" +
					"        <servlet-name>test-servlet</servlet-name>\n" +
					"        <url-pattern>/ts</url-pattern>\n" +
					"    </servlet-mapping>\n" +
					"\n" +
					"</web-app>\n");
		}

		// loop taken from org.apache.catalina.startup.Catalina.load()

		Digester digester = (new Catalina() {
			@Override
			public Digester createStartDigester() {
				return super.createStartDigester();
			}
		}).createStartDigester();

		ServerHolder holder = new ServerHolder();
		digester.push(holder);

		// properties that are fortunately used from within tomcat-*.xml
		System.setProperty("docbase", new File("target").getAbsolutePath());
		System.setProperty("workdir", new File("target/test-classes").getAbsolutePath());
		digester.parse(new File("target/test-classes/etc/tomcat-webapp1.xml"));

		// web.xml will be processed by org.apache.catalina.startup.ContextConfig.configureStart()
		// in response to org.apache.catalina.core.StandardContext.startInternal() sending
		// org.apache.catalina.Lifecycle.CONFIGURE_START_EVENT event.
		// org.apache.catalina.startup.ContextConfig is the default listener in StandardContext
		// org.apache.catalina.startup.ContextConfig.configureContext() is simply iterating over elements
		// in web.xml

		StandardServer server = (StandardServer) holder.getServer();
		Service catalina = server.findService("Catalina");
		Connector connector = catalina.findConnectors()[0];
		assertThat(((StandardThreadExecutor)catalina.getExecutor("default")).getNamePrefix(), equalTo("tomcat-pool-"));

		server.start();

		int port = connector.getLocalPort();
		String response = send(port, "/app1/ts");
		assertTrue(response.endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.destroy();
	}

	@Test
	public void embeddedServerWithExternalConfigurationFilterOnly() throws Exception {
		File webXml = new File("target/WEB-INF/web-filter-only.xml");
		webXml.getParentFile().mkdirs();
		webXml.delete();

		try (FileWriter writer = new FileWriter(webXml)) {
			writer.write("<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
					"    xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd\"\n" +
					"    version=\"4.0\">\n" +
					"\n" +
					"    <filter>\n" +
					"        <filter-name>test-filter</filter-name>\n" +
					"        <filter-class>org.ops4j.pax.web.service.tomcat.internal.EmbeddedTomcatTest$TestFilter</filter-class>\n" +
					"    </filter>\n" +
					"\n" +
					"    <filter-mapping>\n" +
					"        <filter-name>test-filter</filter-name>\n" +
					"        <url-pattern>/*</url-pattern>\n" +
					"    </filter-mapping>\n" +
					"\n" +
					"</web-app>\n");
		}

		// loop taken from org.apache.catalina.startup.Catalina.load()

		Digester digester = (new Catalina() {
			@Override
			public Digester createStartDigester() {
				return super.createStartDigester();
			}
		}).createStartDigester();

		ServerHolder holder = new ServerHolder();
		digester.push(holder);

		// properties that are fortunately used from within tomcat-*.xml
		System.setProperty("docbase", new File("target").getAbsolutePath());
		System.setProperty("workdir", new File("target/test-classes").getAbsolutePath());
		digester.parse(new File("target/test-classes/etc/tomcat-webapp1.xml"));

		// web.xml will be processed by org.apache.catalina.startup.ContextConfig.configureStart()
		// in response to org.apache.catalina.core.StandardContext.startInternal() sending
		// org.apache.catalina.Lifecycle.CONFIGURE_START_EVENT event.
		// org.apache.catalina.startup.ContextConfig is the default listener in StandardContext
		// org.apache.catalina.startup.ContextConfig.configureContext() is simply iterating over elements
		// in web.xml

		StandardServer server = (StandardServer) holder.getServer();
		Service catalina = server.findService("Catalina");
		Connector connector = catalina.findConnectors()[0];
		assertThat(((StandardThreadExecutor)catalina.getExecutor("default")).getNamePrefix(), equalTo("tomcat-pool-"));

		server.start();

		int port = connector.getLocalPort();
		String response = send(port, "/app1/anything");
		// in Tomcat, you can't have filters only without "default" servlet, because that's how
		// org.apache.catalina.core.StandardContextValve.invoke() works.
		assertTrue(response.startsWith("HTTP/1.1 404"));

		server.stop();
		server.destroy();
	}

	private String send(int port, String request, String ... headers) throws IOException {
		Socket s = new Socket();
		s.connect(new InetSocketAddress("127.0.0.1", port));

		s.getOutputStream().write((
				"GET " + request + " HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + port + "\r\n").getBytes());
		for (String header : headers) {
			s.getOutputStream().write((header + "\r\n").getBytes());
		}
		s.getOutputStream().write(("Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s.close();

		return sw.toString();
	}

	private Map<String, String> extractHeaders(String response) throws IOException {
		Map<String, String> headers = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(new StringReader(response))) {
			String line = null;
			while ((line = reader.readLine()) != null) {
				if (line.trim().equals("")) {
					break;
				}
				// I know, security when parsing headers is very important...
				String[] kv = line.split(": ");
				String header = kv[0];
				String value = String.join("", Arrays.asList(kv).subList(1, kv.length));
				headers.put(header, value);
			}
		}
		return headers;
	}

	/**
	 * A class on which Tomcat digester can call {@link #setServer(Server)}
	 */
	public static class ServerHolder {
		private Server server;

		public Server getServer() {
			return server;
		}

		public void setServer(Server server) {
			this.server = server;
		}
	}

	public static class TestServlet extends HttpServlet {
		public TestServlet() {
			System.out.println("Creating TestServlet");
		}

		@Override
		protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
			resp.setContentType("text/plain");
			resp.setCharacterEncoding("UTF-8");
			resp.getWriter().write("OK\n");
			resp.getWriter().close();
		}
	}

	public static class TestFilter extends GenericFilter {
		public TestFilter() {
			System.out.println("Creating TestFilter");
		}

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
			response.setContentType("text/plain");
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write("OK\n");
			response.getWriter().close();
		}
	}

}
