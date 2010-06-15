package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatSharedSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_3_2_4 extends HttpdTomcatSharedSiteManager_3_X<TomcatCommon_3_2_4> {

    HttpdTomcatSharedSiteManager_3_2_4(HttpdTomcatSharedSite tomcatSharedSite) throws SQLException, IOException {
        super(tomcatSharedSite);
    }

    public TomcatCommon_3_2_4 getTomcatCommon() {
        return TomcatCommon_3_2_4.getInstance();
    }

    protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
        final String siteName = httpdSite.getSiteName();
        final String siteDir = siteDirectory.getPath();
        AOServConnector conn = AOServDaemon.getConnector();
        final HttpdTomcatVersion htv = tomcatSite.getHttpdTomcatVersion();
        final HttpdOperatingSystemConfiguration httpdConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        final String wwwgroupDirectory = httpdConfig.getHttpdSharedTomcatsDirectory();

        // Build to RAM first
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ChainWriter out = new ChainWriter(bout);
        try {
            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
            if(!httpdSite.isManual()) out.print(autoWarning);
            out.print("<Server>\n"
                    + "  <xmlmapper:debug level=\"0\" />\n"
                    + "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").print(siteDir).print("/var/log/tomcat.log\" />\n"
                    + "  <Logger name=\"servlet_log\" path=\"").print(siteDir).print("/var/log/servlet.log\" />\n"
                    + "  <Logger name=\"JASPER_LOG\" path=\"").print(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
                    + "\n"
                    + "  <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
            out.print(wwwgroupDirectory).print('/')
                    .print(tomcatSharedSite.getHttpdSharedTomcat().getName())
                    .print("/work/").print(siteName);
            out.print("\" showDebugInfo=\"true\" >\n"
                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n");
            out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\"");
            out.print(" />\n"
                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" ");
            out.print("/>\n"
                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" ");
            out.print("/>\n"
                    + "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n");
            out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleRealm\" debug=\"0\" />\n");
            out.print("    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

            for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
                NetBind netBind=worker.getNetBind();
                String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

                out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
                        + "      <Parameter name=\"handler\" value=\"");
                if(protocol.equals(HttpdJKProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
                else if(protocol.equals(HttpdJKProtocol.AJP13)) out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
                else throw new IllegalArgumentException("Unknown AJP version: "+htv);
                out.print("\"/>\n"
                        + "      <Parameter name=\"port\" value=\"").print(netBind.getPort()).print("\"/>\n");
                IPAddress ip=netBind.getIPAddress();
                if(!ip.isWildcard()) out.print("      <Parameter name=\"inet\" value=\"").print(ip.getIpAddress()).print("\"/>\n");
                out.print("      <Parameter name=\"max_threads\" value=\"30\"/>\n"
                        + "      <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
                        + "      <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
                        + "    </Connector>\n"
                );
            }
            for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                out.print("    <Context path=\"").print(htc.getPath()).print("\" docBase=\"").print(htc.getDocBase()).print("\" debug=\"").print(htc.getDebugLevel()).print("\" reloadable=\"").print(htc.isReloadable()).print("\" />\n");
            }
            out.print("  </ContextManager>\n"
                    + "</Server>\n");
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }
}
