package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.httpd.jboss.HttpdJBossSiteManager;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.SQLUtility;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Manages HttpdTomcatSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdTomcatSiteManager extends HttpdSiteManager {

    /**
     * Gets the specific manager for one type of web site.
     */
    public static HttpdTomcatSiteManager getInstance(HttpdTomcatSite tomcatSite) throws IOException, SQLException {
        HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
        if(stdSite!=null) return HttpdTomcatStdSiteManager.getInstance(stdSite);

        HttpdJBossSite jbossSite = tomcatSite.getHttpdJBossSite();
        if(jbossSite!=null) return HttpdJBossSiteManager.getInstance(jbossSite);

        HttpdTomcatSharedSite shrSite = tomcatSite.getHttpdTomcatSharedSite();
        if(shrSite!=null) return HttpdTomcatSharedSiteManager.getInstance(shrSite);

        throw new SQLException("HttpdTomcatSite must be one of HttpdTomcatStdSite, HttpdJBossSite, or HttpdTomcatSharedSite: "+tomcatSite);
    }

    /**
     * Keeps track of the last start times for each Java VM.
     */
    private static final Map<String,Long> startJVMTimes=new HashMap<String,Long>();

    final protected HttpdTomcatSite tomcatSite;

    protected HttpdTomcatSiteManager(HttpdTomcatSite tomcatSite) {
        super(tomcatSite.getHttpdSite());
        this.tomcatSite = tomcatSite;
    }

    public static String startJVM(int sitePKey) throws IOException, SQLException {
        synchronized(startJVMTimes) {
            AOServConnector conn=AOServDaemon.getConnector();

            HttpdSite httpdSite=conn.httpdSites.get(sitePKey);
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getHostname()+", which is not this server ("+thisAOServer.getHostname()+')');

            HttpdTomcatSite tomcatSite=httpdSite.getHttpdTomcatSite();
            if(tomcatSite==null) throw new SQLException("Unable to find HttpdTomcatSite for HttpdSite #"+sitePKey);

            HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
            if(stdSite!=null) {
                String key="httpd_tomcat_std_site.tomcat_site="+sitePKey;

                // Throw an exception if the site was started recently.
                Long L=startJVMTimes.get(key);
                if(L!=null) {
                    long span=System.currentTimeMillis()-L.longValue();
                    if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                        "Must wait "
                        +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                        +" seconds between Java VM starts, only "
                        +SQLUtility.getMilliDecimal((int)span)
                        +" seconds have passed."
                    ;
                }

                LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                AOServDaemon.suexec(
                    lsa.getLinuxAccount().getUsername().getUsername(),
                    HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/tomcat start",
                    0
                );

                // Make sure we don't start too quickly
                startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
            } else {
                HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                if(shrSite!=null) {
                    HttpdSharedTomcat shrTomcat=shrSite.getHttpdSharedTomcat();
                    String name=shrTomcat.getName();
                    String key="httpd_shared_tomcats.name="+name;

                    // Throw an exception if the site was started recently.
                    Long L=startJVMTimes.get(key);
                    if(L!=null) {
                        long span=System.currentTimeMillis()-L.longValue();
                        if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                            "Must wait "
                            +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                            +" seconds between Java VM starts, only "
                            +SQLUtility.getMilliDecimal((int)span)
                            +" seconds have passed."
                        ;
                    }

                    HttpdSharedTomcatManager.startSharedTomcat(shrTomcat);

                    // Make sure we don't start too quickly
                    startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                } else {
                    HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                    if(jbossSite!=null) {
                        String key="httpd_jboss_site.tomcat_site="+sitePKey;

                        // Throw an exception if the site was started recently.
                        Long L=startJVMTimes.get(key);
                        if(L!=null) {
                            long span=System.currentTimeMillis()-L.longValue();
                            if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                "Must wait "
                                +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                +" seconds between Java VM starts, only "
                                +SQLUtility.getMilliDecimal((int)span)
                                +" seconds have passed."
                            ;
                        }

                        LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                        AOServDaemon.suexec(
                            lsa.getLinuxAccount().getUsername().getUsername(),
                            HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/jboss start",
                            0
                        );

                        // Make sure we don't start too quickly
                        startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                    } else throw new SQLException("Unable to find HttpdTomcatStdSite, HttpdTomcatSharedSite or HttpdJBossSite for HttpdSite #"+sitePKey);
                }
            }

            // Null means all went well
            return null;
        }
    }

    public static String stopJVM(int sitePKey) throws IOException, SQLException {
        final Stat tempStat = new Stat();
        synchronized(startJVMTimes) {
            AOServConnector conn=AOServDaemon.getConnector();

            HttpdSite httpdSite=conn.httpdSites.get(sitePKey);
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getHostname()+", which is not this server ("+thisAOServer.getHostname()+')');

            HttpdTomcatSite tomcatSite=httpdSite.getHttpdTomcatSite();
            if(tomcatSite==null) throw new SQLException("Unable to find HttpdTomcatSite for HttpdSite #"+sitePKey);

            HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
            if(stdSite!=null) {
                String key="httpd_tomcat_std_site.tomcat_site="+sitePKey;

                // Throw an exception if the site was started recently.
                Long L=startJVMTimes.get(key);
                if(L!=null) {
                    long span=System.currentTimeMillis()-L.longValue();
                    if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                        "Must wait "
                        +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                        +" seconds between a Java VM start and stop, only "
                        +SQLUtility.getMilliDecimal((int)span)
                        +" seconds have passed."
                    ;
                }

                LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                AOServDaemon.suexec(
                    lsa.getLinuxAccount().getUsername().getUsername(),
                    HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/tomcat stop",
                    0
                );

                // Make sure we don't start too quickly
                startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
            } else {
                HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                if(shrSite!=null) {
                    HttpdSharedTomcat shrTomcat=shrSite.getHttpdSharedTomcat();
                    String name=shrTomcat.getName();
                    String key="httpd_shared_tomcats.name="+name;

                    // Throw an exception if the site was started recently.
                    Long L=startJVMTimes.get(key);
                    if(L!=null) {
                        long span=System.currentTimeMillis()-L.longValue();
                        if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                            "Must wait "
                            +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                            +" seconds between a Java VM start and stop, only "
                            +SQLUtility.getMilliDecimal((int)span)
                            +" seconds have passed."
                        ;
                    }

                    HttpdSharedTomcatManager.stopSharedTomcat(shrTomcat, tempStat);

                    // Make sure we don't start too quickly
                    startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                } else {
                    HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                    if(jbossSite!=null) {
                        String key="httpd_jboss_site.tomcat_site="+sitePKey;

                        // Throw an exception if the site was started recently.
                        Long L=startJVMTimes.get(key);
                        if(L!=null) {
                            long span=System.currentTimeMillis()-L.longValue();
                            if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                "Must wait "
                                +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                +" seconds between a Java VM start and stop, only "
                                +SQLUtility.getMilliDecimal((int)span)
                                +" seconds have passed."
                            ;
                        }

                        LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                        AOServDaemon.suexec(
                            lsa.getLinuxAccount().getUsername().getUsername(),
                            HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/jboss stop",
                            0
                        );

                        // Make sure we don't start too quickly
                        startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                    } else throw new SQLException("Unable to find HttpdTomcatStdSite, HttpdTomcatSharedSite or HttpdJBossSite for HttpdSite #"+sitePKey);
                }
            }

            // Null means all went well
            return null;
        }
    }
    
    protected boolean enableCgi() {
        return true;
    }

    protected boolean enablePhp() {
        return true;
    }
    
    public boolean enableAnonymousFtp() {
        return true;
    }

    public abstract TomcatCommon getTomcatCommon();

    /*
     * TODO: Put at the beginning of the build site methods (to make the directory w/ the proper permissions)        
            wwwDirUF.getStat(tempStat);
            if(!tempStat.exists() || tempStat.getUID()==UnixFile.ROOT_GID) {
                getHttpdSiteManager(site).buildSiteDirectory();
            }

                if(!tempStat.exists()) wwwDirUF.mkdir();
                wwwDirUF.setMode(
                    (
                        tomcatSite!=null
                        && tomcatSite.getHttpdTomcatSharedSite()!=null
                        && (tomcatSite.getHttpdTomcatVersion().isTomcat3_1(conn) || tomcatSite.getHttpdTomcatVersion().isTomcat3_2_4(conn))
                        && tomcatSite.getHttpdTomcatSharedSite().getHttpdSharedTomcat().isSecure()
                    ) ? 01770 : 0770
                );
     */

        /* TODO: Put at end of site creation
                // Now that the contents are all successfully created, update the directory
                // owners to make sure we don't cover up this directory in the future
                wwwDirUF.chown(httpdUID, lsgGID);
         */
    // TODO: Call from appropriate place
    protected byte[] buildServerXml() {
        AOServConnector conn = AOServDaemon.getConnector();
        // Add or rebuild the server.xml files for standard or Tomcat sites.
        final HttpdTomcatVersion htv = tomcatSite.getHttpdTomcatVersion();
        final boolean isTomcat3_1 = htv.isTomcat3_1(conn);
        final boolean isTomcat3_2_4 = htv.isTomcat3_2_4(conn);
        final boolean isTomcat4_1_X = htv.isTomcat4_1_X(conn);
        final boolean isTomcat5_5_X = htv.isTomcat5_5_X(conn);
        final boolean isTomcat6_0_X = htv.isTomcat6_0_X(conn);
        HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
        boolean isStandard=stdSite!=null;
        boolean isJBoss=tomcatSite.getHttpdJBossSite()!=null;
        if(
            isTomcat3_1
            || isTomcat3_2_4
            || isStandard
            || isJBoss
        ) {
            HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
            String autoWarning=getAutoWarningXml();
            boolean isShared=shrSite!=null;

            String confServerXML=siteDir+"/conf/server.xml";
            UnixFile confServerXMLFile=new UnixFile(confServerXML);
            if(!httpdSite.isManual() || !confServerXMLFile.getStat(tempStat).exists()) {
                UnixFile newConfServerXML=new UnixFile(confServerXML+".new");
                try {
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            newConfServerXML.getSecureOutputStream(
                                isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                ?UnixFile.ROOT_UID
                                :httpdSite.getLinuxServerAccount().getUID().getID(),
                                httpdSite.getLinuxServerGroup().getGID().getID(),
                                isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                ?0640
                                :0660,
                                true
                            )
                        )
                    );
                    try {
                        if(isTomcat3_1) {
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
                            if (isStandard) out.print(siteDir).print("/work");
                            else if (isShared) out.print(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
                                    .print(shrSite.getHttpdSharedTomcat().getName())
                                    .print("/work/").print(siteName);
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
                        } else if(isTomcat3_2_4) {
                            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
                            if(!httpdSite.isManual()) out.print(autoWarning);
                            out.print("<Server>\n"
                                    + "  <xmlmapper:debug level=\"0\" />\n"
                                    + "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").print(siteDir).print("/var/log/tomcat.log\" />\n"
                                    + "  <Logger name=\"servlet_log\" path=\"").print(siteDir).print("/var/log/servlet.log\" />\n"
                                    + "  <Logger name=\"JASPER_LOG\" path=\"").print(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
                                    + "\n"
                                    + "  <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
                            if(isStandard) out.print(siteDir).print("/work");
                            else if(isShared) out.print(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
                                    .print(shrSite.getHttpdSharedTomcat().getName())
                                    .print("/work/").print(siteName);
                            out.print("\" showDebugInfo=\"true\" >\n"
                                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
                                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                                    + "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n");
                            if(isJBoss) out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.Jdk12Interceptor\" />\n");
                            out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\"");
                            if(isJBoss) out.print(" noCookies=\"false\"");
                            out.print(" />\n"
                                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
                                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" ");
                            if(isJBoss) out.print("prefix=\"/servlet/\" ");
                            out.print("/>\n"
                                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" ");
                            if(isJBoss) out.print("suppress=\"false\" ");
                            out.print("/>\n"
                                    + "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
                                    + "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n");
                            if(isJBoss) out.print("    <RequestInterceptor className=\"org.jboss.tomcat.security.JBossSecurityMgrRealm\" />\n");
                            else out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleRealm\" debug=\"0\" />\n");
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
                                if(!ip.isWildcard()) out.print("      <Parameter name=\"inet\" value=\"").print(ip.getIPAddress()).print("\"/>\n");
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
                        } else if(isTomcat4_1_X || isTomcat5_5_X) {
                            List<HttpdWorker> hws=tomcatSite.getHttpdWorkers();
                            if(hws.size()!=1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #"+httpdSite.getPkey()+", found "+hws.size());
                            HttpdWorker hw=hws.get(0);
                            String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
                            if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
                                throw new SQLException("HttpdWorker #"+hw.getPkey()+" for HttpdTomcatStdSite #"+httpdSite.getPkey()+" must be AJP13 but it is "+hwProtocol);
                            }
                            if(!httpdSite.isManual()) out.print(autoWarning);
                            NetBind shutdownPort=stdSite.getTomcat4ShutdownPort();
                            if(shutdownPort==null) throw new SQLException("Unable to find shutdown port for HttpdTomcatStdSite="+stdSite);
                            String shutdownKey=stdSite.getTomcat4ShutdownKey();
                            if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdTomcatStdSite="+stdSite);
                            out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
                            if(isTomcat5_5_X) {
                                out.print("  <GlobalNamingResources>\n"
                                        + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                                        + "              type=\"org.apache.catalina.UserDatabase\"\n"
                                        + "       description=\"User database that can be updated and saved\"\n"
                                        + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                                        + "          pathname=\"conf/tomcat-users.xml\" />\n"
                                        + "   </GlobalNamingResources>\n");
                            } else if(isTomcat4_1_X) {
                                out.print("  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" debug=\"0\"/>\n"
                                        + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" debug=\"0\"/>\n"
                                        + "  <GlobalNamingResources>\n"
                                        + "    <Resource name=\"UserDatabase\" auth=\"Container\" type=\"org.apache.catalina.UserDatabase\" description=\"User database that can be updated and saved\"/>\n"
                                        + "    <ResourceParams name=\"UserDatabase\">\n"
                                        + "      <parameter>\n"
                                        + "        <name>factory</name>\n"
                                        + "        <value>org.apache.catalina.users.MemoryUserDatabaseFactory</value>\n"
                                        + "      </parameter>\n"
                                        + "      <parameter>\n"
                                        + "        <name>pathname</name>\n"
                                        + "        <value>conf/tomcat-users.xml</value>\n"
                                        + "      </parameter>\n"
                                        + "    </ResourceParams>\n"
                                        + "  </GlobalNamingResources>\n");
                            }
                            out.print("  <Service name=\"").print(isTomcat5_5_X ? "Catalina" : "Tomcat-Apache").print("\">\n"
                                    + "    <Connector\n"
                                    //+ "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                                    //+ "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                                    //+ "      minProcessors=\"2\"\n"
                                    //+ "      maxProcessors=\"200\"\n"
                                    //+ "      enableLookups=\"true\"\n"
                                    //+ "      redirectPort=\"443\"\n"
                                    //+ "      acceptCount=\"10\"\n"
                                    //+ "      debug=\"0\"\n"
                                    //+ "      connectionTimeout=\"20000\"\n"
                                    //+ "      useURIValidationHack=\"false\"\n"
                                    //+ "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                                    );
                            if (isTomcat4_1_X) {
                                out.print("      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n");
                            }
                            out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
                            if(isTomcat5_5_X) out.print("      enableLookups=\"false\"\n");
                            out.print("      minProcessors=\"2\"\n"
                                    + "      maxProcessors=\"200\"\n"
                                    + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                                    + "      acceptCount=\"10\"\n"
                                    + "      debug=\"0\"\n"
                                    + "      protocol=\"AJP/1.3\"\n"
                                    + "    />\n"
                                    + "    <Engine name=\"").print(isTomcat5_5_X ? "Catalina" : "Tomcat-Apache").print("\" defaultHost=\"localhost\" debug=\"0\">\n");
                            if(isTomcat5_5_X) {
                                out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                                        + "          resourceName=\"UserDatabase\" />\"\n");
                            } else if(isTomcat4_1_X) {
                                out.print("      <Logger\n"
                                        + "        className=\"org.apache.catalina.logger.FileLogger\"\n"
                                        + "        directory=\"var/log\"\n"
                                        + "        prefix=\"catalina_log.\"\n"
                                        + "        suffix=\".txt\"\n"
                                        + "        timestamp=\"true\"\n"
                                        + "      />\n");
                                out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" debug=\"0\" resourceName=\"UserDatabase\" />\n");
                            } else {
                                out.print("      <Realm className=\"org.apache.catalina.realm.MemoryRealm\" />\n");
                            }
                            out.print("      <Host\n"
                                    + "        name=\"localhost\"\n"
                                    + "        debug=\"0\"\n"
                                    + "        appBase=\"webapps\"\n"
                                    + "        unpackWARs=\"true\"\n");
                            if(
                                isTomcat4_1_X
                                || isTomcat5_5_X
                            ) {
                                out.print("        autoDeploy=\"true\"\n");
                            }
                            if(isTomcat5_5_X) {
                                out.print("        xmlValidation=\"false\"\n"
                                        + "        xmlNamespaceAware=\"false\"\n");
                            }
                            out.print("      >\n");
                            if(isTomcat4_1_X) {
                                out.print("        <Logger\n"
                                        + "          className=\"org.apache.catalina.logger.FileLogger\"\n"
                                        + "          directory=\"var/log\"\n"
                                        + "          prefix=\"localhost_log.\"\n"
                                        + "          suffix=\".txt\"\n"
                                        + "          timestamp=\"true\"\n"
                                        + "        />\n");
                            }
                            for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                out.print("        <Context\n");
                                if(htc.getClassName()!=null) out.print("          className=\"").print(htc.getClassName()).print("\"\n");
                                out.print("          cookies=\"").print(htc.useCookies()).print("\"\n"
                                        + "          crossContext=\"").print(htc.allowCrossContext()).print("\"\n"
                                        + "          docBase=\"").print(htc.getDocBase()).print("\"\n"
                                        + "          override=\"").print(htc.allowOverride()).print("\"\n"
                                        + "          path=\"").print(htc.getPath()).print("\"\n"
                                        + "          privileged=\"").print(htc.isPrivileged()).print("\"\n"
                                        + "          reloadable=\"").print(htc.isReloadable()).print("\"\n"
                                        + "          useNaming=\"").print(htc.useNaming()).print("\"\n");
                                if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").print(htc.getWrapperClass()).print("\"\n");
                                out.print("          debug=\"").print(htc.getDebugLevel()).print("\"\n");
                                if(htc.getWorkDir()!=null) out.print("          workDir=\"").print(htc.getWorkDir()).print("\"\n");
                                List<HttpdTomcatParameter> parameters=htc.getHttpdTomcatParameters();
                                List<HttpdTomcatDataSource> dataSources=htc.getHttpdTomcatDataSources();
                                if(parameters.isEmpty() && dataSources.isEmpty()) {
                                    out.print("        />\n");
                                } else {
                                    out.print("        >\n");
                                    // Parameters
                                    for(HttpdTomcatParameter parameter : parameters) {
                                        writeHttpdTomcatParameter(htv, conn, parameter, out);
                                    }
                                    // Data Sources
                                    for(HttpdTomcatDataSource dataSource : dataSources) {
                                        writeHttpdTomcatDataSource(htv, conn, dataSource, out);
                                    }
                                    out.print("        </Context>\n");
                                }
                            }
                            out.print("      </Host>\n"
                                    + "    </Engine>\n"
                                    + "  </Service>\n"
                                    + "</Server>\n");
                        } else throw new SQLException("Unsupported version of Tomcat: "+htv.getTechnologyVersion(conn).getVersion());
                    } finally {
                        out.close();
                    }

                    // Flag the JVM as needing a restart if the server.xml file has been modified
                    if(httpdSite.getDisableLog()==null) {
                        boolean needsRestart;
                        if(confServerXMLFile.getStat(tempStat).exists()) {
                            needsRestart=!confServerXMLFile.contentEquals(newConfServerXML);
                        } else needsRestart=true;
                        if(needsRestart) {
                            int pkey=httpdSite.getPkey();
                            if(isShared) {
                                sharedTomcatsNeedingRestarted.add(shrSite.getHttpdSharedTomcat());
                            } else {
                                sitesNeedingRestarted.put(httpdSite);
                            }
                        }
                    }

                    // Move the file into place
                    newConfServerXML.renameTo(confServerXMLFile);
                } catch(IOException err) {
                    System.err.println("Error on file: "+newConfServerXML.getPath());
                    throw err;
                }
            } else {
                try {
                    stripFilePrefix(
                        confServerXMLFile,
                        autoWarning,
                        tempStat
                    );
                } catch(IOException err) {
                    // Errors OK because this is done in manual mode and they might have symbolic linked stuff
                }
            }
        }
    }
    
    // TODO: Move this to start/stop/restart implementations
    /*
            String siteName=hs.getSiteName();
            try {
                if(hs.getDisableLog()==null) {
                    // Enabled, make sure running and auto
                    UnixFile pidUF=getPIDUnixFile(hs);
                    UnixFile daemonUF=getDaemonUnixFile(hs);
                    if(pidUF!=null && (sitesNeedingRestarted.contains(hs) || !pidUF.getStat(tempStat).exists())) {
                        startHttpdSite(hs);
                    }
                    if(daemonUF!=null && !daemonUF.getStat(tempStat).exists()) enableHttpdSite(hs, tempStat);
                } else {
                    // Disabled, make sure stopped and not auto
                    UnixFile daemonUF=getDaemonUnixFile(hs);
                    UnixFile pidUF=getPIDUnixFile(hs);
                    if(daemonUF!=null && daemonUF.getStat(tempStat).exists()) disableHttpdSite(hs, tempStat);
                    if(pidUF!=null && pidUF.getStat(tempStat).exists()) stopHttpdSite(hs, tempStat);
                }
            } catch(IOException err) {
                System.err.println("disableAndEnableHttpdSites error on site: "+siteName);
                throw err;
            }
     */
    private static UnixFile getPIDUnixFile(HttpdSite hs) throws IOException, SQLException {
        HttpdTomcatSite hts=hs.getHttpdTomcatSite();
        if(hts!=null) {
            HttpdJBossSite hjs=hts.getHttpdJBossSite();
            if(hjs!=null) return new UnixFile(hs.getInstallDirectory()+"/var/run/jboss.pid");
            HttpdTomcatStdSite htss=hts.getHttpdTomcatStdSite();
            if(htss!=null) return new UnixFile(hs.getInstallDirectory()+"/var/run/tomcat.pid");
        }
        return null;
    }

    private static UnixFile getDaemonUnixFile(HttpdSite hs) throws IOException, SQLException {
        HttpdTomcatSite hts=hs.getHttpdTomcatSite();
        if(hts!=null) {
            HttpdJBossSite hjs=hts.getHttpdJBossSite();
            if(hjs!=null) return new UnixFile(hs.getInstallDirectory()+"/daemon/jboss");
            HttpdTomcatStdSite htss=hts.getHttpdTomcatStdSite();
            if(htss!=null) return new UnixFile(hs.getInstallDirectory()+"/daemon/tomcat");
        }
        return null;
    }

    private static void startHttpdSite(HttpdSite hs) throws IOException, SQLException {
        controlHttpdSite(hs, "start");
    }
    
    private static void stopHttpdSite(HttpdSite hs, Stat tempStat) throws IOException, SQLException {
        UnixFile pidUF=getPIDUnixFile(hs);
        if(pidUF!=null && pidUF.getStat(tempStat).exists()) controlHttpdSite(hs, "stop");
    }

    private static void controlHttpdSite(HttpdSite hs, String action) throws IOException, SQLException {
        HttpdTomcatSite hst=hs.getHttpdTomcatSite();
        if(hst!=null) {
            HttpdJBossSite hjs=hst.getHttpdJBossSite();
            if(hjs!=null) {
                LinuxServerAccount lsa=hs.getLinuxServerAccount();
                AOServDaemon.suexec(
                    lsa.getLinuxAccount().getUsername().getUsername(),
                    HttpdSite.WWW_DIRECTORY+'/'+hs.getSiteName()+"/bin/jboss "+action,
                    0
                );
            } else {
                HttpdTomcatStdSite htss=hst.getHttpdTomcatStdSite();
                if(htss!=null) {
                    LinuxServerAccount lsa=hs.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+hs.getSiteName()+"/bin/tomcat "+action,
                        0
                    );
                }
            }
        }
    }

    private static void enableHttpdSite(HttpdSite hs,Stat tempStat) throws IOException, SQLException {
        HttpdTomcatSite hst=hs.getHttpdTomcatSite();
        if(hst!=null) {
            HttpdJBossSite hjs=hst.getHttpdJBossSite();
            if(hjs!=null) {
                UnixFile uf=new UnixFile(hs.getInstallDirectory()+"/daemon/jboss");
                if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/jboss").chown(
                    hs.getLinuxServerAccount().getUID().getID(),
                    hs.getLinuxServerGroup().getGID().getID()
                );
            } else {
                HttpdTomcatStdSite htss=hst.getHttpdTomcatStdSite();
                if(htss!=null) {
                    UnixFile uf=new UnixFile(hs.getInstallDirectory()+"/daemon/tomcat");
                    if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/tomcat").chown(
                        hs.getLinuxServerAccount().getUID().getID(),
                        hs.getLinuxServerGroup().getGID().getID()
                    );
                }
            }
        }
    }

    private static void disableHttpdSite(HttpdSite hs, Stat tempStat) throws IOException {
        disableHttpdSite(hs.getSiteName(), tempStat);
    }

    private static void disableHttpdSite(String siteName, Stat tempStat) throws IOException {
        UnixFile jbossUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon/jboss");
        if(jbossUF.getStat(tempStat).exists()) jbossUF.delete();

        UnixFile tomcatUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon/tomcat");
        if(tomcatUF.getStat(tempStat).exists()) tomcatUF.delete();
    }

    /**
     * In addition to the standard values, also protects the /WEB-INF/ and /META-INF/ directories of all contexts.
     * This is only done when the "use_apache" flag is on.  Otherwise, Tomcat should protect the necessary
     * paths.
     */
    @Override
    public SortedSet<Location> getRejectedLocations() {
        // If not using Apache, let Tomcat do its own protection
        SortedSet<Location> standardRejectedLocations = super.getRejectedLocations();
        if(!tomcatSite.useApache()) return standardRejectedLocations;

        SortedSet<Location> rejectedLocations = new TreeSet<Location>(standardRejectedLocations);
        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
            String path=htc.getPath();
            rejectedLocations.add(new Location(false, path+"/META-INF/"));
            rejectedLocations.add(new Location(false, path+"/WEB-INF/"));
        }
        return Collections.unmodifiableSortedSet(rejectedLocations);
    }
    
    /**
     * Gets the Jk worker for the site.
     */
    protected abstract HttpdWorker getHttpdWorker() throws IOException, SQLException;

    @Override
    public SortedSet<JkSetting> getJkSettings() {
        final String jkCode = getHttpdWorker().getCode().getCode();
        SortedSet<JkSetting> settings = new TreeSet<JkSetting>();
        if(tomcatSite.useApache()) {
            // Using Apache for static content, send specific requests to Tomcat
            for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
                String path=context.getPath();
                settings.add(new JkSetting(true, path+"/j_security_check", jkCode));
                settings.add(new JkSetting(true, path+"/servlet/*", jkCode));
                settings.add(new JkSetting(true, path+"/*.do", jkCode));
                settings.add(new JkSetting(true, path+"/*.jsp", jkCode));
                settings.add(new JkSetting(true, path+"/*.jspa", jkCode));
                settings.add(new JkSetting(true, path+"/*.vm", jkCode));
                settings.add(new JkSetting(true, path+"/*.xml", jkCode));
            }
        } else {
            // Not using Apache, send as much as possible to Tomcat
            settings.add(new JkSetting(true, "/*", jkCode));
            for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
                String path=context.getPath();
                if(enableCgi()) settings.add(new JkSetting(false, path+"/cgi-bin/*", jkCode));
                if(enablePhp()) settings.add(new JkSetting(false, path+"/*.php", jkCode));
            }
        }
    }

    public SortedMap<String,WebAppSettings> getWebapps() {
        SortedMap<String,WebAppSettings> webapps = new TreeMap<String,WebAppSettings>();

        // Set up all of the webapps
        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
            webapps.put(
                htc.getPath(),
                new WebAppSettings(
                    htc.getDocBase(),
                    "All",
                    "Indexes IncludesNOEXEC",
                    enableCgi()
                )
            );
        }
        return webapps;
    }
}
