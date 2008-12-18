package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatStdSite version 3.1 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_3_1 extends HttpdTomcatStdSiteManager_3_X<TomcatCommon_3_1> {

    HttpdTomcatStdSiteManager_3_1(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    public TomcatCommon_3_1 getTomcatCommon() {
        return TomcatCommon_3_1.getInstance();
    }

    protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
        final String siteDir = siteDirectory.getPath();
        AOServConnector conn = AOServDaemon.getConnector();
        final HttpdTomcatVersion htv = tomcatSite.getHttpdTomcatVersion();

        // Build to RAM first
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ChainWriter out = new ChainWriter(bout);
        try {
            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
            if(!httpdSite.isManual()) out.print(autoWarning);
            out.print("<Server>\n"
                    + "    <xmlmapper:debug level=\"0\" />\n"
                    + "    <Logger name=\"tc_log\"\n"
                    + "            path=\"").print(siteDir).print("/var/log/tomcat.log\"\n"
                    + "            customOutput=\"yes\" />\n"
                    + "    <Logger name=\"servlet_log\"\n"
                    + "            path=\"").print(siteDir).print("/var/log/servlet.log\"\n"
                    + "            customOutput=\"yes\" />\n"
                    + "    <Logger name=\"JASPER_LOG\"\n"
                    + "            path=\"").print(siteDir).print("/var/log/jasper.log\"\n"
                    + "            verbosityLevel = \"INFORMATION\" />\n"
                    + "    <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
            out.print(siteDir).print("/work");
            out.print("\" >\n"
                    + "        <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                    + "        <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
                    + "        <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                    + "        <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n"
                    + "        <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper\" debug=\"0\" />\n"
                    + "        <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" />\n"
                    + "        <RequestInterceptor className=\"org.apache.tomcat.request.SecurityCheck\" />\n"
                    + "        <RequestInterceptor className=\"org.apache.tomcat.request.FixHeaders\" />\n"
            );
            for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
                NetBind netBind=worker.getNetBind();
                String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

                out.print("        <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
                        + "            <Parameter name=\"handler\" value=\"");
                if(protocol.equals(HttpdJKProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
                else if(protocol.equals(HttpdJKProtocol.AJP13)) throw new IllegalArgumentException("Tomcat Version "+htv+" does not support AJP version: "+protocol);
                else throw new IllegalArgumentException("Unknown AJP version: "+htv);
                out.print("\"/>\n"
                        + "            <Parameter name=\"port\" value=\"").print(netBind.getPort()).print("\"/>\n");
                IPAddress ip=netBind.getIPAddress();
                if(!ip.isWildcard()) out.print("            <Parameter name=\"inet\" value=\"").print(ip.getIPAddress()).print("\"/>\n");
                out.print("            <Parameter name=\"max_threads\" value=\"30\"/>\n"
                        + "            <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
                        + "            <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
                        + "        </Connector>\n"
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
