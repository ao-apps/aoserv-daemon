package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdStaticSite;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Username;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract public class HttpdSiteManager {

    /**
     * The directory that logs are stored in.
     */
    public static final String LOG_DIR="/logs";

    /**
     * Gets the specific manager for one type of web site.
     */
    private static HttpdSiteManager getHttpdSiteManager(HttpdSite site) throws IOException, SQLException {
        HttpdStaticSite staticSite=site.getHttpdStaticSite();
        if(staticSite!=null) return new HttpdStaticSiteManager(staticSite);

        HttpdTomcatSite tomcatSite=site.getHttpdTomcatSite();
        if(tomcatSite!=null) {
            AOServConnector connector=AOServDaemon.getConnector();

            HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();

            HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
            HttpdTomcatSharedSite shrSite = tomcatSite.getHttpdTomcatSharedSite();
            HttpdJBossSite jbossSite = tomcatSite.getHttpdJBossSite();
            if(stdSite!=null) {
                if(htv.isTomcat3_1(connector)) return new HttpdTomcatStdSiteManager_3_1(stdSite);
                if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatStdSiteManager_3_2_4(stdSite);
                if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatStdSiteManager_4_1_X(stdSite);
                if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatStdSiteManager_5_5_X(stdSite);
                if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatStdSiteManager_6_0_X(stdSite);
                throw new SQLException("Unsupported version of standard Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+stdSite);
            } else if(jbossSite!=null) {
                String jbossVersion = jbossSite.getHttpdJBossVersion().getTechnologyVersion(connector).getVersion();
                if(jbossVersion.equals("2.2.2")) return new HttpdJBossSiteManager_2_2_2(jbossSite);
            } else if(shrSite!=null) {
                if(htv.isTomcat3_1(connector)) return new HttpdTomcatSharedSiteManager_3_1(shrSite);
                else if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatSharedSiteManager_3_2_4(shrSite);
                else if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatSharedSiteManager_4_1_X(shrSite);
                else if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatSharedSiteManager_5_5_X(shrSite);
                else if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatSharedSiteManager_6_0_X(shrSite);
                else throw new SQLException("Unsupported version of shared Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+stdSite);
            } else throw new SQLException("HttpdTomcatSite must be one of HttpdTomcatStdSite, HttpdTomcatSharedSite, or HttpdJBossSite: "+tomcatSite);
        }

        throw new SQLException("HttpdSite must be either HttpdStaticSite and HttpdTomcatSite: "+site);
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
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    static void doRebuildHttpdSites(
        AOServer aoServer,
        List<File> deleteFileList,
        AOServConnector conn,
        IntList serversNeedingRestarted,
        IntList sitesNeedingRestarted,
        IntList sharedTomcatsNeedingRestarted,
        Stat tempStat
    ) throws IOException, SQLException {
        int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();

        /*
         * Get values used in the rest of the method.
         */
        final String username = (osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) ? LinuxAccount.APACHE : LinuxAccount.HTTPD;

        Username httpdUsername=conn.usernames.get(username);
        if(httpdUsername==null) throw new SQLException("Unable to find Username: "+username);
        LinuxAccount httpdLA=httpdUsername.getLinuxAccount();
        if(httpdLA==null) throw new SQLException("Unable to find LinuxAccount: "+httpdUsername);
        LinuxServerAccount httpdLSA=httpdLA.getLinuxServerAccount(aoServer);
        if(httpdLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+httpdLA+" on "+aoServer.getServer().getHostname());
        int httpdUID=httpdLSA.getUID().getID();
        LinuxServerAccount awstatsLSA=aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS);
        if(awstatsLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.AWSTATS+" on "+aoServer.getServer().getHostname());
        int awstatsUID=awstatsLSA.getUID().getID();

        // Iterate through each site
        List<HttpdSite> sites=aoServer.getHttpdSites();

        // The log directories that exist but are not used will be removed
        String[] list=new File(LOG_DIR).list();
        List<String> logDirectories=new SortedArrayList<String>(list.length);
        for(int c=0;c<list.length;c++) {
            String dirname=list[c];
            if(!dirname.equals("lost+found")) logDirectories.add(dirname);
        }

        // The www directories that exist but are not used will be removed
        list=new File(HttpdSite.WWW_DIRECTORY).list();
        List<String> wwwDirectories=new SortedArrayList<String>(list.length);
        for(int c=0;c<list.length;c++) {
            String dirname=list[c];
            if(
                !dirname.equals("lost+found")
                && !dirname.equals("aquota.user")
            ) {
                wwwDirectories.add(dirname);
            }
        }

        // The config directory should only contain files referenced in the database
        list=new File(HttpdManager.CONF_HOSTS).list();
        List<String> hostConfFiles=new SortedArrayList<String>(list.length);
        for(int c=0;c<list.length;c++) hostConfFiles.add(list[c]);

        // Each site gets its own shared FTP space
        list=new File(FTPManager.SHARED_FTP_DIRECTORY).list();
        List<String> ftpDirectories=new SortedArrayList<String>(list.length);
        for(int c=0;c<list.length;c++) ftpDirectories.add(list[c]);

        int len=sites.size();
        for(int c=0;c<len;c++) {
            /*
             * Get values used in the rest of the loop.
             */
            final HttpdSiteManager manager = getHttpdSiteManager(sites.get(c));

            // Log files
            UnixFile logDirectory = new UnixFile(LOG_DIR+'/'+manager.httpdSite.getSiteName());
            manager.configureLogDirectory(logDirectory);
            logDirectories.remove(manager.httpdSite.getSiteName());

            // Create and fill in any incomplete installations.
            UnixFile wwwDirectory = new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+manager.httpdSite.getSiteName());
            manager.configureSiteDirectory(wwwDirectory);
            wwwDirectories.remove(manager.httpdSite.getSiteName());

            /*
            List<HttpdSiteBind> binds=site.getHttpdSiteBinds();
            final String siteName=site.getSiteName();

            LinuxServerAccount lsa=site.getLinuxServerAccount();
            int lsaUID=lsa.getUID().getID();

            LinuxServerGroup lsg=site.getLinuxServerGroup();
            int lsgGID=lsg.getGID().getID();

            // Add or rebuild the server.xml files for standard or Tomcat sites.
            if(tomcatSite!=null) {
                final HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
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
                    String autoWarning=getAutoWarning(site);
                    boolean isShared=shrSite!=null;

                    String confServerXML=siteDir+"/conf/server.xml";
                    UnixFile confServerXMLFile=new UnixFile(confServerXML);
                    if(!site.isManual() || !confServerXMLFile.getStat(tempStat).exists()) {
                        UnixFile newConfServerXML=new UnixFile(confServerXML+".new");
                        try {
                            ChainWriter out=new ChainWriter(
                                new BufferedOutputStream(
                                    newConfServerXML.getSecureOutputStream(
                                        isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                        ?UnixFile.ROOT_UID
                                        :site.getLinuxServerAccount().getUID().getID(),
                                        site.getLinuxServerGroup().getGID().getID(),
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
                                    if(!site.isManual()) out.print(autoWarning);
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
                                    if(!site.isManual()) out.print(autoWarning);
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
                                    if(hws.size()!=1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #"+site.getPkey()+", found "+hws.size());
                                    HttpdWorker hw=hws.get(0);
                                    String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
                                    if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
                                        throw new SQLException("HttpdWorker #"+hw.getPkey()+" for HttpdTomcatStdSite #"+site.getPkey()+" must be AJP13 but it is "+hwProtocol);
                                    }
                                    if(!site.isManual()) out.print(autoWarning);
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
                                out.flush();
                                out.close();
                            }

                            // Flag the JVM as needing a restart if the server.xml file has been modified
                            if(site.getDisableLog()==null) {
                                boolean needsRestart;
                                if(confServerXMLFile.getStat(tempStat).exists()) {
                                    needsRestart=!confServerXMLFile.contentEquals(newConfServerXML);
                                } else needsRestart=true;
                                if(needsRestart) {
                                    int pkey=site.getPkey();
                                    if(isShared) {
                                        if(!sharedTomcatsNeedingRestarted.contains(pkey)) sharedTomcatsNeedingRestarted.add(shrSite.getHttpdSharedTomcat().getPkey());
                                    } else {
                                        if(!sitesNeedingRestarted.contains(pkey)) sitesNeedingRestarted.add(pkey);
                                    }
                                }
                            }

                            // Move the file into place
                            newConfServerXML.renameTo(confServerXMLFile);
                        } catch(IOException err) {
                            System.err.println("Error on file: "+newConfServerXML.getFilename());
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
            */

            // Add the web server config files if they do not exist.
            // The shared config part
            hostConfFiles.remove(manager.httpdSite.getSiteName());
            UnixFile sharedFile=new UnixFile(HttpdManager.CONF_HOSTS+'/'+manager.httpdSite.getSiteName());
            if(!manager.httpdSite.isManual() || !sharedFile.getStat(tempStat).exists()) {
                // Build to a temporary buffer
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ChainWriter out = new ChainWriter(bout);
                manager.createApacheSiteConfiguration(out);
                /*
                String newSharedConfig=sharedConfig+".new";
                UnixFile newSharedFile=new UnixFile(newSharedConfig);
                ChainWriter out=new ChainWriter(
                    new BufferedOutputStream(
                        newSharedFile.getSecureOutputStream(
                            UnixFile.ROOT_UID,
                            lsgGID,
                            0640,
                            true
                        )
                    )
                );
                try {
                    out.print("    ServerAdmin ").print(site.getServerAdmin()).print("\n"
                            + "\n");

                    if(tomcatSite!=null) {
                        final HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
                        final boolean isTomcat3_1 = htv.isTomcat3_1(conn);
                        final boolean isTomcat3_2_4 = htv.isTomcat3_2_4(conn);
                        final boolean isTomcat4_1_X = htv.isTomcat4_1_X(conn);
                        final boolean isTomcat5_5_X = htv.isTomcat5_5_X(conn);
                        final boolean isTomcat6_0_X = htv.isTomcat6_0_X(conn);
                        HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                        HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                        HttpdTomcatSharedSite sharedSite=tomcatSite.getHttpdTomcatSharedSite();

                        boolean useApache=tomcatSite.useApache();
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586 || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print("    <IfModule !sapi_apache2.c>\n"
                                    + "        <IfModule !mod_php5.c>\n"
                                    + "            Action php4-script /cgi-bin/php\n"
                                    + "            AddHandler php4-script .php .php3 .php4\n"
                                    + "        </IfModule>\n"
                                    + "    </IfModule>\n");
                        } else {
                            out.print("    <IfModule !mod_php4.c>\n"
                                    + "        <IfModule !mod_php5.c>\n"
                                    + "            Include conf/options/cgi_php4\n"
                                    + "        </IfModule>\n"
                                    + "    </IfModule>\n");
                        }

                        if(useApache) {
                            if(osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586 && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                out.print("    Include conf/options/shtml_standard\n"
                                          + "    Include conf/options/mod_rewrite\n"
                                          + "\n");
                            }
                        }
                        if(stdSite!=null || jbossSite!=null) {
                            if(isTomcat3_1 || isTomcat3_2_4) {
                                out.print("    <IfModule mod_jserv.c>\n"
                                        + "        ApJServDefaultProtocol ajpv12\n"
                                        + "        ApJServSecretKey DISABLED\n"
                                        + "        ApJServMountCopy on\n"
                                        + "        ApJServLogLevel notice\n"
                                        + "        ApJServMount default /root\n"
                                        + "        AddType text/do .do\n"
                                        + "        AddHandler jserv-servlet .do\n"
                                        + "        AddType text/jsp .jsp\n"
                                        + "        AddHandler jserv-servlet .jsp\n"
                                        + "        AddType text/xml .xml\n"
                                        + "        AddHandler jserv-servlet .xml\n"
                                        + "        <IfModule mod_rewrite.c>\n"
                                        + "            # Support jsessionid\n"
                                        + "            RewriteEngine on\n"
                                        + "            RewriteRule ^(/.*;jsessionid=.*)$ $1 [T=jserv-servlet]\n"
                                        + "        </IfModule>\n"
                                        + "    </IfModule>\n"
                                        + "\n");
                            }
                        } else if(sharedSite!=null) {
                            if(osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64 && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                out.print("    Include conf/wwwgroup/").print(sharedSite.getHttpdSharedTomcat().getName()).print("\n"
                                        + "\n");
                            }
                        } else throw new IllegalArgumentException("Unsupported HttpdTomcatSite type: "+site.getPkey());

                        // The CGI user info
                        out.print("    <IfModule mod_suexec.c>\n"
                                + "        SuexecUserGroup ").print(lsa.getLinuxAccount().getUsername()).print(' ').print(lsg.getLinuxGroup().getName()).print("\n"
                                + "    </IfModule>\n"
                                + "\n");

                        // Write the standard restricted URL patterns
                        out.print("    # Standard protected URLs\n");
                        for(int d=0;d<standardProtectedURLs.length;d++) {
                            out.print("    <LocationMatch \"").print(standardProtectedURLs[d]).print("\">\n"
                                    + "\tAllowOverride None\n"
                                    + "\tDeny from All\n"
                                    + "    </LocationMatch>\n"
                            );
                        }
                        out.print("    # Protect automatic backups\n"
                                + "    <IfModule mod_rewrite.c>\n"
                                + "        RewriteEngine on\n"
                                + "\n"
                                + "        # Redirect past automatic backups\n"
                                + "        RewriteRule ^(.*).do~$ $1.do [L,R=permanent]\n"
                                + "        RewriteRule ^(.*).jsp~$ $1.jsp [L,R=permanent]\n"
                                + "        RewriteRule ^(.*).jspa~$ $1.jspa [L,R=permanent]\n"
                                + "        RewriteRule ^(.*).php~$ $1.php [L,R=permanent]\n"
                                + "        RewriteRule ^(.*).shtml~$ $1.shtml [L,R=permanent]\n"
                                + "        RewriteRule ^(.*).vm~$ $1.vm [L,R=permanent]\n"
                                + "        RewriteRule ^(.*).xml~$ $1.xml [L,R=permanent]\n"
                                + "\n"
                                + "        # Protect dangerous request methods\n"
                                + "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
                                + "        RewriteRule .* - [F]\n"
                                + "    </IfModule>\n");
                        // Write the authenticated locations
                        List<HttpdSiteAuthenticatedLocation> hsals = site.getHttpdSiteAuthenticatedLocations();
                        if(!hsals.isEmpty()) {
                            out.print("    # Authenticated Locations\n");
                            for(HttpdSiteAuthenticatedLocation hsal : hsals) {
                                out.print("    <").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(" \"").print(hsal.getPath()).print("\">\n");
                                if(hsal.getAuthUserFile().length()>0 || hsal.getAuthGroupFile().length()>0) out.print("        AuthType Basic\n");
                                if(hsal.getAuthName().length()>0) out.print("        AuthName \"").print(hsal.getAuthName()).print("\"\n");
                                if(hsal.getAuthUserFile().length()>0) out.print("        AuthUserFile \"").print(hsal.getAuthUserFile()).print("\"\n");
                                if(hsal.getAuthGroupFile().length()>0) out.print("        AuthGroupFile \"").print(hsal.getAuthGroupFile()).print("\"\n");
                                if(hsal.getRequire().length()>0) out.print("        require ").print(hsal.getRequire()).print('\n');
                                out.print("    </").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(">\n");
                            }
                        }

                        // Set up all of the webapps
                        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                            String path=htc.getPath();
                            String docBase=htc.getDocBase();
                            if(path.length()==0) {
                                out.print("    # Set up the default webapp\n"
                                        + "    DocumentRoot ").print(docBase).print("\n"
                                        + "    <Directory ").print(docBase).print(">\n"
                                        + "        Allow from All\n"
                                        + "        AllowOverride All\n"
                                        + "        Order allow,deny\n"
                                        + "        Options Indexes IncludesNOEXEC\n"
                                        + "    </Directory>\n");
                            } else if(useApache) {
                                out.print("    # Set up the ").print(path).print(" webapp\n"
                                        + "    AliasMatch ^").print(path).print("$ ").print(docBase).print("\n"
                                        + "    AliasMatch ^").print(path).print("/(.*) ").print(docBase).print("/$1\n"
                                        + "    <Directory \"").print(docBase).print("\">\n"
                                        + "        Allow from All\n"
                                        + "        AllowOverride All\n"
                                        + "        Order allow,deny\n"
                                        + "        Options Indexes IncludesNOEXEC\n"
                                        + "    </Directory>\n");
                            }
                            if(useApache) {
                                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    out.print("    <Directory \"").print(docBase).print("/cgi-bin\">\n"
                                            + "        <IfModule mod_ssl.c>\n"
                                            + "            SSLOptions +StdEnvVars\n"
                                            + "        </IfModule>\n"
                                            + "        SetHandler cgi-script\n"
                                            + "        Options ExecCGI\n"
                                            + "    </Directory>\n");
                                } else {
                                    out.print("    ScriptAlias ").print(path).print("/cgi-bin/ ").print(docBase).print("/cgi-bin/\n"
                                            + "    <Directory ").print(docBase).print("/cgi-bin>\n"
                                            + "        Options ExecCGI\n"
                                            + "        <IfModule mod_ssl.c>\n"
                                            + "            SSLOptions +StdEnvVars\n"
                                            + "        </IfModule>\n"
                                            + "        Allow from All\n"
                                            + "        Order allow,deny\n"
                                            + "    </Directory>\n");
                                }
                            } else {
                                out.print("    <IfModule mod_jk.c>\n"
                                        + "        ScriptAlias ").print(path).print("/cgi-bin/ ").print(docBase).print("/cgi-bin/\n"
                                        + "        <Directory ").print(docBase).print("/cgi-bin>\n"
                                        + "            Options ExecCGI\n"
                                        + "            <IfModule mod_ssl.c>\n"
                                        + "                SSLOptions +StdEnvVars\n"
                                        + "            </IfModule>\n"
                                        + "            Allow from All\n"
                                        + "            Order allow,deny\n"
                                        + "        </Directory>\n"
                                        + "    </IfModule>\n");
                            }
                            if(useApache) {
                                out.print("    <Location ").print(path).print("/META-INF/>\n"
                                        + "        AllowOverride None\n"
                                        + "        Deny from All\n"
                                        + "    </Location>\n"
                                        + "    <Location ").print(path).print("/WEB-INF/>\n"
                                        + "        AllowOverride None\n"
                                        + "        Deny from All\n"
                                        + "    </Location>\n");
                            }
                            out.print('\n');
                        }

                        List<HttpdWorker> workers=tomcatSite.getHttpdWorkers();
                        String ajp12Code=null;
                        if(isTomcat3_1 || isTomcat3_2_4) {
                            // Look for the first AJP12 worker
                            int ajp12Port=-1;
                            for(HttpdWorker hw : workers) {
                                if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) {
                                    ajp12Port=hw.getNetBind().getPort().getPort();
                                    ajp12Code=hw.getCode().getCode();
                                    break;
                                }
                            }
                            if(ajp12Port!=-1) {
                                out.print("    <IfModule mod_jserv.c>\n"
                                          + "        ApJServDefaultPort ").print(ajp12Port).print("\n");
                                for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                    String path=htc.getPath();
                                    String relPath=useApache?path+"/servlet":path;
                                    out.print("        ApJServMount ").print(relPath.length()==0?"/":relPath).print(' ').print(path.length()==0?"/"+HttpdTomcatContext.ROOT_DOC_BASE:path).print("\n");
                                }
                                out.print("    </IfModule>\n"
                                          + "\n");
                            }
                        }

                        int ajp13Port=-1;
                        String ajp13Code=null;
                        if(!(isTomcat3_1 || isTomcat3_2_4) && sharedSite!=null) {
                            HttpdWorker hw=sharedSite.getHttpdSharedTomcat().getTomcat4Worker();
                            ajp13Port=hw.getNetBind().getPort().getPort();
                            ajp13Code=hw.getCode().getCode();
                        } else {
                            for(HttpdWorker hw : workers) {
                                if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP13)) {
                                    ajp13Port=hw.getNetBind().getPort().getPort();
                                    ajp13Code=hw.getCode().getCode();
                                    break;
                                }
                            }
                        }
                        String jkCode=ajp13Code!=null?ajp13Code:ajp12Code;
                        out.print("    <IfModule mod_jk.c>\n");
                                //+ "        # Redirect past automatic backups\n"
                                //+ "        <IfModule mod_rewrite.c>\n"
                                //+ "            RewriteEngine on\n"
                                //+ "            RewriteRule ^(.*).do~$ $1.do [L,R=permanent]\n"
                                //+ "            RewriteRule ^(.*).jsp~$ $1.jsp [L,R=permanent]\n"
                                //+ "            RewriteRule ^(.*).jspa~$ $1.jspa [L,R=permanent]\n"
                                //+ "            RewriteRule ^(.*).vm~$ $1.vm [L,R=permanent]\n"
                                //+ "            RewriteRule ^(.*).xml~$ $1.xml [L,R=permanent]\n"
                                //+ "        </IfModule>\n");
                        if(useApache) {
                            for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
                                String path=context.getPath();
                                out.print("        JkMount ").print(path).print("/j_security_check ").print(jkCode).print("\n"
                                + "        JkMount ").print(path).print("/servlet/* ").print(jkCode).print("\n"
                                + "        JkMount ").print(path).print("/*.do ").print(jkCode).print("\n"
                                + "        JkMount ").print(path).print("/*.jsp ").print(jkCode).print("\n"
                                + "        JkMount ").print(path).print("/*.jspa ").print(jkCode).print("\n"
                                + "        JkMount ").print(path).print("/*.vm ").print(jkCode).print("\n"
                                + "        JkMount ").print(path).print("/*.xml ").print(jkCode).print("\n");
                            }
                        } else {
                            out.print("        JkMount /* ").print(jkCode).print("\n");
                            if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                out.print("        JkUnMount /*.php ").print(jkCode).print("\n"
                                        + "        JkUnMount /cgi-bin/* ").print(jkCode).print("\n");
                            }
                        }
                        out.print("        # Remove jsessionid for non-jk requests\n"
                                + "        <IfModule mod_rewrite.c>\n"
                                + "            RewriteEngine On\n"
                                + "            RewriteRule ^(.*);jsessionid=.*$ $1\n"
                                + "        </IfModule>\n"
                                + "    </IfModule>\n"
                                + "\n");
                    } else throw new IllegalArgumentException("Unsupported HttpdSite type for HttpdSite #"+site.getPkey());
                } finally {
                    out.flush();
                    out.close();
                }
                */
                out.flush();
                out.close();
                byte[] newSharedFileContents = bout.toByteArray();
                if(!sharedFile.getStat(tempStat).exists() || !sharedFile.contentEquals(newSharedFileContents)) {
                    /*
                     * TODO
                    for(HttpdSiteBind hsb : site.getHttpdSiteBinds()) {
                        int hsPKey=hsb.getHttpdBind().getHttpdServer().getPkey();
                        if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                    }
                     */
                    UnixFile newSharedFile=new UnixFile(sharedFile.getFilename()+".new");
                    OutputStream newSharedFileOut=newSharedFile.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        manager.httpdSite.getLinuxServerGroup().getGID().getID(),
                        0640,
                        true
                    );
                    try {
                        newSharedFileOut.write(newSharedFileContents);
                    } finally {
                        newSharedFileOut.flush();
                        newSharedFileOut.close();
                    }
                    newSharedFile.renameTo(sharedFile);
                }
            }

            // Each of the binds
            /*
            for(HttpdSiteBind bind : binds) {
                NetBind netBind=bind.getHttpdBind().getNetBind();
                String ipAddress=netBind.getIPAddress().getIPAddress();
                int port=netBind.getPort().getPort();
                String bindConfig=CONF_HOSTS+'/'+siteName+'_'+ipAddress+'_'+port;
                hostConfFiles.remove(siteName+'_'+ipAddress+'_'+port);
                UnixFile bindFile=new UnixFile(bindConfig);
                if(!bind.isManual() || !bindFile.getStat(tempStat).exists()) {
                    String newBindConfig=bindConfig+".new";
                    UnixFile newBindFile=new UnixFile(newBindConfig);
                    writeHttpdSiteBindFile(bind, newBindFile, bind.getDisableLog()!=null?HttpdSite.DISABLED:siteName);
                    if(!bindFile.getStat(tempStat).exists() || !bindFile.contentEquals(newBindFile)) {
                        int hsPKey=bind.getHttpdBind().getHttpdServer().getPkey();
                        if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                        newBindFile.renameTo(bindFile);
                    } else newBindFile.delete();
                }
            }
             */

            /*
             * Make the private FTP space, if needed.
             */
            manager.configureFtpDirectory(new UnixFile(FTPManager.SHARED_FTP_DIRECTORY+'/'+manager.httpdSite.getSiteName()));
            ftpDirectories.remove(manager.httpdSite.getSiteName());
        }

        /*
         * Delete any extra files once the rebuild is done
         */
        /*
        for(int c=0;c<hostConfFiles.size();c++) deleteFileList.add(new File(CONF_HOSTS, hostConfFiles.get(c)));
        for(int c=0;c<logDirectories.size();c++) deleteFileList.add(new File(LOG_DIR, logDirectories.get(c)));
        for(int c=0;c<wwwDirectories.size();c++) {
            String siteName=wwwDirectories.get(c);
            stopHttpdSite(siteName, tempStat);
            disableHttpdSite(siteName, tempStat);
            String fullPath=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            if(!aoServer.isHomeUsed(fullPath)) {
                deleteFileList.add(new File(fullPath));
            }
        }
        for(int c=0;c<ftpDirectories.size();c++) deleteFileList.add(new File(FTPManager.SHARED_FTP_DIRECTORY, ftpDirectories.get(c)));
         */
    }

    final protected HttpdSite httpdSite;

    HttpdSiteManager(HttpdSite httpdSite) {
        this.httpdSite = httpdSite;
    }

    /**
     * Configures the log directory if needed.  Removed any needed log directories from the list of previously existing log directories.
     * Must be in /logs/${site_name}
     */
    protected void configureLogDirectory(UnixFile logDirectory) throws IOException {
        Stat tempStat = new Stat();
        logDirectory.getStat(tempStat);
        if(!tempStat.exists()) {
            logDirectory.mkdir();
            logDirectory.getStat(tempStat);
        }
        int awstatsUID = httpdSite.getAOServer().getLinuxServerAccount(LinuxAccount.AWSTATS).getUID().getID();
        int lsgGID = httpdSite.getLinuxServerGroup().getGID().getID();
        if(tempStat.getUID()!=awstatsUID || tempStat.getGID()!=lsgGID) logDirectory.chown(awstatsUID, lsgGID);
        if(tempStat.getMode()!=0750) logDirectory.setMode(0750);
    }

    /**
     * (Re)builds the site directory from scratch if it doesn't exist.
     * Creates or recreates resources as necessary.
     * Also performs an automatic upgrade of resources if appropriate for the site.
     * Also reconfigures any config files within the directory if appropriate for the site type and settings.
     */
    protected abstract void configureSiteDirectory(UnixFile wwwDirectory);

    /**
     * Configures the anonymous FTP directory associated with this site.
     */
    protected void configureFtpDirectory(UnixFile ftpDirectory) throws IOException {
        if(!ftpDirectory.getStat().exists()) {
            ftpDirectory.mkdir(
                false,
                0775,
                httpdSite.getLinuxServerAccount().getUID().getID(),
                httpdSite.getLinuxServerGroup().getGID().getID()
            );
        }
    }
    
    /**
     * Creates the Apache configuration file for the site.  This doesn't deal with the virtual
     * hosting configuration or ip/port/hostname mappings, this is the configuration for the
     * site overall.
     */
    protected abstract void createApacheSiteConfiguration(ChainWriter out);
}
