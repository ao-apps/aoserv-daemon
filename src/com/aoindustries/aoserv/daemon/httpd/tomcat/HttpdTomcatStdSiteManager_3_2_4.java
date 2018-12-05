/*
 * Copyright 2007-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Version;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages PrivateTomcatSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_3_2_4 extends HttpdTomcatStdSiteManager_3_X<TomcatCommon_3_2_4> {

	HttpdTomcatStdSiteManager_3_2_4(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	@Override
	public TomcatCommon_3_2_4 getTomcatCommon() {
		return TomcatCommon_3_2_4.getInstance();
	}

	@Override
	protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
		final String siteDir = siteDirectory.getPath();
		AOServConnector conn = AOServDaemon.getConnector();
		final Version htv = tomcatSite.getHttpdTomcatVersion();

		// Build to RAM first
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
			if(!httpdSite.isManual()) out.print(autoWarning);
			out.print("<Host>\n"
					+ "  <xmlmapper:debug level=\"0\" />\n"
					+ "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").encodeXmlAttribute(siteDir).print("/var/log/tomcat.log\" />\n"
					+ "  <Logger name=\"servlet_log\" path=\"").encodeXmlAttribute(siteDir).print("/var/log/servlet.log\" />\n"
					+ "  <Logger name=\"JASPER_LOG\" path=\"").encodeXmlAttribute(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
					+ "\n"
					+ "  <ContextManager debug=\"0\" home=\"").encodeXmlAttribute(siteDir).print("\" workDir=\"").encodeXmlAttribute(siteDir).print("/work\" showDebugInfo=\"true\" >\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleRealm\" debug=\"0\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

			for(Worker worker : tomcatSite.getHttpdWorkers()) {
				Bind netBind=worker.getBind();
				String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

				out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
						+ "      <Parameter name=\"handler\" value=\"");
				if(protocol.equals(JkProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
				else if(protocol.equals(JkProtocol.AJP13)) out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
				else throw new IllegalArgumentException("Unknown AJP version: "+htv);
				out.print("\"/>\n"
						+ "      <Parameter name=\"port\" value=\"").encodeXmlAttribute(netBind.getPort().getPort()).print("\"/>\n");
				InetAddress ip=netBind.getIpAddress().getInetAddress();
				if(!ip.isUnspecified()) out.print("      <Parameter name=\"inet\" value=\"").encodeXmlAttribute(ip).print("\"/>\n");
				out.print("      <Parameter name=\"max_threads\" value=\"30\"/>\n"
						+ "      <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
						+ "      <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
						+ "    </Connector>\n"
				);
			}
			for(Context htc : tomcatSite.getHttpdTomcatContexts()) {
				out.print("    <Context path=\"").encodeXmlAttribute(htc.getPath()).print("\" docBase=\"").encodeXmlAttribute(htc.getDocBase()).print("\" debug=\"").encodeXmlAttribute(htc.getDebugLevel()).print("\" reloadable=\"").encodeXmlAttribute(htc.isReloadable()).print("\" />\n");
			}
			out.print("  </ContextManager>\n"
					+ "</Server>\n");
		}
		return bout.toByteArray();
	}
}
