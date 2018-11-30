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
 * Manages PrivateTomcatSite version 3.1 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_3_1 extends HttpdTomcatStdSiteManager_3_X<TomcatCommon_3_1> {

	HttpdTomcatStdSiteManager_3_1(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	@Override
	public TomcatCommon_3_1 getTomcatCommon() {
		return TomcatCommon_3_1.getInstance();
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
					+ "    <xmlmapper:debug level=\"0\" />\n"
					+ "    <Logger name=\"tc_log\"\n"
					+ "            path=\"").encodeXmlAttribute(siteDir).print("/var/log/tomcat.log\"\n"
					+ "            customOutput=\"yes\" />\n"
					+ "    <Logger name=\"servlet_log\"\n"
					+ "            path=\"").encodeXmlAttribute(siteDir).print("/var/log/servlet.log\"\n"
					+ "            customOutput=\"yes\" />\n"
					+ "    <Logger name=\"JASPER_LOG\"\n"
					+ "            path=\"").encodeXmlAttribute(siteDir).print("/var/log/jasper.log\"\n"
					+ "            verbosityLevel = \"INFORMATION\" />\n"
					+ "    <ContextManager debug=\"0\" home=\"").encodeXmlAttribute(siteDir).print("\" workDir=\"").encodeXmlAttribute(siteDir).print("/work\" >\n"
					+ "        <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
					+ "        <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
					+ "        <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
					+ "        <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n"
					+ "        <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper\" debug=\"0\" />\n"
					+ "        <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" />\n"
					+ "        <RequestInterceptor className=\"org.apache.tomcat.request.SecurityCheck\" />\n"
					+ "        <RequestInterceptor className=\"org.apache.tomcat.request.FixHeaders\" />\n"
			);
			for(Worker worker : tomcatSite.getHttpdWorkers()) {
				Bind netBind=worker.getBind();
				String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

				out.print("        <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
						+ "            <Parameter name=\"handler\" value=\"");
				if(protocol.equals(JkProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
				else if(protocol.equals(JkProtocol.AJP13)) throw new IllegalArgumentException("Tomcat Version "+htv+" does not support AJP version: "+protocol);
				else throw new IllegalArgumentException("Unknown AJP version: "+htv);
				out.print("\"/>\n"
						+ "            <Parameter name=\"port\" value=\"").encodeXmlAttribute(netBind.getPort().getPort()).print("\"/>\n");
				InetAddress ip=netBind.getIpAddress().getInetAddress();
				if(!ip.isUnspecified()) out.print("            <Parameter name=\"inet\" value=\"").encodeXmlAttribute(ip).print("\"/>\n");
				out.print("            <Parameter name=\"max_threads\" value=\"30\"/>\n"
						+ "            <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
						+ "            <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
						+ "        </Connector>\n"
				);
			}
			for(Context htc : tomcatSite.getHttpdTomcatContexts()) {
				out.print("    <Context path=\"").encodeXmlAttribute(htc.getPath()).print("\" docBase=\"").encodeXmlAttribute(htc.getDocBase()).print("\" debug=\"").encodeXmlAttribute(htc.getDebugLevel()).print("\" reloadable=\"").encodeXmlAttribute(htc.isReloadable()).print("\" />\n");
			}
			out.print("  </ContextManager>\n"
					+ "</Host>\n");
		}
		return bout.toByteArray();
	}
}
