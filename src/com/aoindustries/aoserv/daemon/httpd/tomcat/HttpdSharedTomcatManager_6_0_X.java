/*
 * Copyright 2008-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.SortedArrayList;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdSharedTomcat version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_6_0_X extends HttpdSharedTomcatManager<TomcatCommon_6_0_X> {

    HttpdSharedTomcatManager_6_0_X(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
    
    TomcatCommon_6_0_X getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }

    void buildSharedTomcatDirectory(UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        /*
         * Get values used in the rest of the loop.
         */
        final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
        final Stat tempStat = new Stat();
        final AOServer aoServer = AOServDaemon.getThisAOServer();
        final HttpdTomcatVersion htv=sharedTomcat.getHttpdTomcatVersion();
        final String tomcatDirectory=htv.getInstallDirectory();
        final TomcatCommon tomcatCommon = getTomcatCommon();
        final LinuxServerAccount lsa = sharedTomcat.getLinuxServerAccount();
        final int lsaUID = lsa.getUid().getID();
        final LinuxServerGroup lsg = sharedTomcat.getLinuxServerGroup();
        final int lsgGID = lsg.getGid().getID();
        final String wwwGroupDir = sharedTomcatDirectory.getPath();
        final String wwwDirectory = httpdConfig.getHttpdSitesDirectory();
        final UnixFile daemonUF = new UnixFile(sharedTomcatDirectory, "daemon", false);
        final UnixFile confUF = new UnixFile(sharedTomcatDirectory, "conf", false);

        // Create and fill in the directory if it does not exist or is owned by root.
        UnixFile workUF = new UnixFile(sharedTomcatDirectory, "work", false);
        UnixFile innerWorkUF = new UnixFile(workUF, "Catalina", false);

        boolean needRestart=false;
        if (!sharedTomcatDirectory.getStat(tempStat).exists() || sharedTomcatDirectory.getStat(tempStat).getUid() == UnixFile.ROOT_GID) {

            // Create the /wwwgroup/name/...

            // 001
            if (!sharedTomcatDirectory.getStat(tempStat).exists()) sharedTomcatDirectory.mkdir();
            sharedTomcatDirectory.setMode(0770);
            new UnixFile(sharedTomcatDirectory, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            confUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
            new UnixFile(confUF, "Catalina", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
            FileUtils.ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
            FileUtils.mkdir(wwwGroupDir+"/temp", 0770, lsaUID, lsgGID);
            UnixFile varUF = new UnixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

            workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
            FileUtils.mkdir(innerWorkUF.getPath(), 0750, lsaUID, lsgGID);

            PostgresServer postgresServer=aoServer.getPreferredPostgresServer();
            String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            FileUtils.ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
            FileUtils.ln("../../.."+tomcatDirectory+"/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
            FileUtils.ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
            FileUtils.ln("../../.."+tomcatDirectory+"/bin/digest.sh", wwwGroupDir+"/bin/digest.sh", lsaUID, lsgGID);

            String profileFile = wwwGroupDir + "/bin/profile";
            LinuxAccountManager.setBashProfile(lsa, profileFile);

            UnixFile profileUF = new UnixFile(profileFile);
            ChainWriter out = new ChainWriter(
                new BufferedOutputStream(
                    profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false)
                )
            );
            try {
                out.print("#!/bin/sh\n"
                          + "\n");

                out.print(". /etc/profile\n"
                        + ". ").print(osConfig.getScriptInclude("jdk"+tomcatCommon.getDefaultJdkVersion()+".sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("php-"+httpdConfig.getDefaultPhpVersion()+".sh")).print('\n');
                if(postgresServerMinorVersion!=null) out.print(". ").print(osConfig.getScriptInclude("postgresql-"+postgresServerMinorVersion+".sh")).print('\n');
                out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
                        + "\n"
                        + "umask 002\n"
                        + "export DISPLAY=:0.0\n"
                        + "\n"
                        + "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
                        + "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
                        + "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
                );
                out.print("\n"
                        + "export PATH=\"${PATH}:").print(wwwGroupDir).print("/bin\"\n"
                        + "\n"
                        + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M'\n"
                        + "\n");
                out.print(". ").print(wwwGroupDir).print("/bin/profile.sites\n"
                        + "\n"
                        + "for SITE in $SITES\n"
                        + "do\n"
                        + "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
                        + "done\n");
            } finally {
                out.close();
            }

            UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
            out=new ChainWriter(
                new BufferedOutputStream(
                    tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false)
                )
            );
            try {
                out.print("#!/bin/sh\n"
                        + "\n"
                        + "TOMCAT_HOME=\"").print(wwwGroupDir).print("\"\n"
                        + "\n"
                        + "if [ \"$1\" = \"start\" ]; then\n"
                        + "    \"$0\" stop\n"
                        + "    \"$0\" daemon &\n"
                        + "    echo \"$!\" >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                        + "elif [ \"$1\" = \"stop\" ]; then\n"
                        + "    if [ -f \"${TOMCAT_HOME}/var/run/tomcat.pid\" ]; then\n"
                        + "        kill `cat \"${TOMCAT_HOME}/var/run/tomcat.pid\"`\n"
                        + "        rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                        + "    fi\n"
                        + "    if [ -f \"${TOMCAT_HOME}/var/run/java.pid\" ]; then\n"
                        + "        . \"$TOMCAT_HOME/bin/profile\"\n"
                        + "        if [ \"$SITES\" != \"\" ]; then\n"
                        + "            cd \"$TOMCAT_HOME\"\n"
                        + "            \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
                        + "        fi\n"
                        + "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
                        + "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
                        + "    fi\n"
                        + "elif [ \"$1\" = \"daemon\" ]; then\n"
                        + "    cd \"$TOMCAT_HOME\"\n"
                        + "    . \"$TOMCAT_HOME/bin/profile\"\n"
                        + "\n"
                        + "    if [ \"$SITES\" != \"\" ]; then\n"
                        + "        while [ 1 ]; do\n"
                        + "            mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err.old\"\n"
                        + "            \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
                        + "            echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
                        + "            wait\n"
                        + "            RETCODE=\"$?\"\n"
                        + "            echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
                        + "            sleep 5\n"
                        + "        done\n"
                        + "    fi\n"
                        + "    rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                        + "else\n"
                        + "    echo \"Usage:\"\n"
                        + "    echo \"tomcat {start|stop}\"\n"
                        + "    echo \"        start - start tomcat\"\n"
                        + "    echo \"        stop  - stop tomcat\"\n"
                        + "fi\n"
                );
            } finally {
                out.close();
            }

            FileUtils.ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

            UnixFile shutdown=new UnixFile(wwwGroupDir+"/bin/shutdown.sh");
            out=new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
            try {
                out.print("#!/bin/sh\n"
                          + "exec \"").print(wwwGroupDir).print("/bin/tomcat\" stop\n");
            } finally {
                out.close();
            }

            UnixFile startup=new UnixFile(wwwGroupDir+"/bin/startup.sh");
            out=new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
            try {
                out.print("#!/bin/sh\n"
                          + "exec \"").print(wwwGroupDir).print("/bin/tomcat\" start\n");
            } finally {
                out.close();
            }

            FileUtils.ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", wwwGroupDir+"/bin/tomcat-juli.jar", lsaUID, lsgGID);
            FileUtils.ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);
            FileUtils.ln("../../.."+tomcatDirectory+"/bin/version.sh", wwwGroupDir+"/bin/version.sh", lsaUID, lsgGID);

            // Create the lib directory and all contents
            FileUtils.mkdir(wwwGroupDir+"/lib", 0770, lsaUID, lsgGID);
            FileUtils.lnAll("../../.."+tomcatDirectory+"/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);

            if(postgresServerMinorVersion!=null) {
                String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
                if(postgresPath!=null) FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
            }
            String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
            if(mysqlConnectorPath!=null) {
                String filename = new UnixFile(mysqlConnectorPath).getFile().getName();
                FileUtils.ln("../../.."+mysqlConnectorPath, wwwGroupDir+"/lib/"+filename, lsaUID, lsgGID);
            }

            {
                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.policy");
                new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                cp.chown(lsaUID, lsgGID).setMode(0660);
            }
            {
                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.properties");
                new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
                cp.chown(lsaUID, lsgGID).setMode(0660);
            }
            {
                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/context.xml");
                new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
                cp.chown(lsaUID, lsgGID).setMode(0660);
            }
            {
                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/logging.properties");
                new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
                cp.chown(lsaUID, lsgGID).setMode(0660);
            }
            {
                UnixFile tuUF=new UnixFile(wwwGroupDir+"/conf/tomcat-users.xml");
                new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tuUF, false);
                tuUF.chown(lsaUID, lsgGID).setMode(0660);
            }
            {
                UnixFile webUF=new UnixFile(wwwGroupDir+"/conf/web.xml");
                new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(webUF, false);
                webUF.chown(lsaUID, lsgGID).setMode(0660);
            }

            // Set the ownership to avoid future rebuilds of this directory
            sharedTomcatDirectory.chown(lsaUID, lsgGID);

            needRestart=true;
        }

        // always rebuild profile.sites file
        UnixFile newSitesFileUF = new UnixFile(sharedTomcatDirectory, "bin/profile.sites.new", false);
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true)
            )
        );
        List<HttpdTomcatSharedSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
        try {
            out.print("export SITES=\"");
            boolean didOne=false;
            for(int j = 0; j< sites.size(); j++) {
                HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
                if(!hs.isDisabled()) {
                    if (didOne) out.print(' ');
                    else didOne=true;
                    out.print(hs.getSiteName());
                }
            }
            out.print("\"\n");
        } finally {
            out.close();
        }
        // flag as needing a restart if this file is different than any existing
        UnixFile sitesFile = new UnixFile(sharedTomcatDirectory, "bin/profile.sites", false);
        if(!sitesFile.getStat(tempStat).exists() || !newSitesFileUF.contentEquals(sitesFile)) {
            needRestart=true;
            UnixFile backupFile=new UnixFile(sharedTomcatDirectory, "bin/profile.sites.old", false);
            if(sitesFile.getStat(tempStat).exists()) sitesFile.renameTo(backupFile);
            newSitesFileUF.renameTo(sitesFile);
        } else newSitesFileUF.delete();

        // make work directories and remove extra work dirs
        List<String> workFiles = new SortedArrayList<String>();
        String[] wlist = innerWorkUF.getFile().list();
        if(wlist!=null) {
			workFiles.addAll(Arrays.asList(wlist));
        }
        for (int j = 0; j< sites.size(); j++) {
            HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
            if(!hs.isDisabled()) {
                String subwork = hs.getPrimaryHttpdSiteURL().getHostname().toString();
                workFiles.remove(subwork);
                UnixFile workDir = new UnixFile(innerWorkUF, subwork, false);
                if (!workDir.getStat(tempStat).exists()) {
                    workDir
                        .mkdir()
                        .chown(
                            lsaUID,
                            sites.get(j)
                                .getHttpdTomcatSite()
                                .getHttpdSite()
                                .getLinuxServerGroup()
                                .getGid()
                                .getID()
                        )
                        .setMode(0750)
                    ;
                }
            }
        }
        for (int c = 0; c < workFiles.size(); c++) {
            deleteFileList.add(new File(innerWorkUF.getFile(), workFiles.get(c)));
        }

        // Rebuild the server.xml
        String autoWarning = getAutoWarningXml();
        String autoWarningOld = getAutoWarningXmlOld();
        String confServerXML=wwwGroupDir+"/conf/server.xml";
        UnixFile confServerXMLUF=new UnixFile(confServerXML);
        if(!sharedTomcat.isManual() || !confServerXMLUF.getStat(tempStat).exists()) {
            String newConfServerXML=wwwGroupDir+"/conf/server.xml.new";
            UnixFile newConfServerXMLUF=new UnixFile(newConfServerXML);
            out=new ChainWriter(
                new BufferedOutputStream(
                    newConfServerXMLUF.getSecureOutputStream(lsaUID, lsgGID, 0660, true)
                )
            );
            try {
                HttpdWorker hw=sharedTomcat.getTomcat4Worker();
                if(!sharedTomcat.isManual()) out.print(autoWarning);
                NetBind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
                if(shutdownPort==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+sharedTomcat);
                String shutdownKey=sharedTomcat.getTomcat4ShutdownKey();
                if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+sharedTomcat);
                out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\">\n"
                        + "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n"
                        + "  <Listener className=\"org.apache.catalina.core.JasperListener\" />\n"
                        + "  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" />\n"
                        + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
                        + "  <GlobalNamingResources>\n"
                        + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                        + "              type=\"org.apache.catalina.UserDatabase\"\n"
                        + "              description=\"User database that can be updated and saved\"\n"
                        + "              factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                        + "              pathname=\"conf/tomcat-users.xml\" />\n"
                        + "  </GlobalNamingResources>\n"
                        + "  <Service name=\"Catalina\">\n"
                        + "    <Connector\n"
                        + "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                        + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                        + "      protocol=\"AJP/1.3\"\n"
                        + "      redirectPort=\"8443\"\n"
                        + "    />\n"
                        + "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
                        + "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                        + "             resourceName=\"UserDatabase\" />\"\n");
                for(int c=0;c<sites.size();c++) {
                    HttpdSite hs=sites.get(c).getHttpdTomcatSite().getHttpdSite();
                    if(!hs.isDisabled()) {
                        DomainName primaryHostname=hs.getPrimaryHttpdSiteURL().getHostname();
                        out.print("      <Host\n"
                                + "        name=\"").print(primaryHostname.toString()).print("\"\n"
                                + "        appBase=\"").print(wwwDirectory).print('/').print(hs.getSiteName()).print("/webapps\"\n"
                                + "        unpackWARs=\"true\"\n"
                                + "        autoDeploy=\"true\"\n"
                                + "        xmlValidation=\"false\"\n"
                                + "        xmlNamespaceAware=\"false\"\n"
                                + "      >\n");
                        List<String> usedHostnames=new SortedArrayList<String>();
                        usedHostnames.add(primaryHostname.toString());
                        List<HttpdSiteBind> binds=hs.getHttpdSiteBinds();
                        for(int d=0;d<binds.size();d++) {
                            HttpdSiteBind bind=binds.get(d);
                            List<HttpdSiteURL> urls=bind.getHttpdSiteURLs();
                            for(int e=0;e<urls.size();e++) {
                                DomainName hostname=urls.get(e).getHostname();
                                if(!usedHostnames.contains(hostname.toString())) {
                                    out.print("        <Alias>").print(hostname.toString()).print("</Alias>\n");
                                    usedHostnames.add(hostname.toString());
                                }
                            }
                            // When listed first, also include the IP addresses as aliases
                            if(hs.listFirst()) {
                                String ip=bind.getHttpdBind().getNetBind().getIPAddress().getInetAddress().toString();
                                if(!usedHostnames.contains(ip)) {
                                    out.print("        <Alias>").encodeHtml(ip).print("</Alias>\n");
                                    usedHostnames.add(ip);
                                }
                            }
                        }
                        HttpdTomcatSite tomcatSite=hs.getHttpdTomcatSite();
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
                                    tomcatCommon.writeHttpdTomcatParameter(parameter, out);
                                }
                                // Data Sources
                                for(HttpdTomcatDataSource dataSource : dataSources) {
                                    tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
                                }
                                out.print("        </Context>\n");
                            }
                        }
                        out.print("      </Host>\n");
                    }
                }
                out.print("    </Engine>\n"
                        + "  </Service>\n"
                        + "</Server>\n");
            } finally {
                out.close();
            }

            // Must restart JVM if this file has changed
            if(
                !confServerXMLUF.getStat(tempStat).exists()
                || !newConfServerXMLUF.contentEquals(confServerXMLUF)
            ) {
                needRestart=true;
                newConfServerXMLUF.renameTo(confServerXMLUF);
            } else newConfServerXMLUF.delete();
        } else {
            try {
                FileUtils.stripFilePrefix(
                    confServerXMLUF,
                    autoWarningOld,
                    tempStat
                );
                FileUtils.stripFilePrefix(
                    confServerXMLUF,
                    autoWarning,
                    tempStat
                );
            } catch(IOException err) {
                // Errors OK because this is done in manual mode and they might have symbolic linked stuff
            }
        }

        // Enable/Disable
        UnixFile daemonSymlink = new UnixFile(daemonUF, "tomcat", false);
        if(!sharedTomcat.isDisabled()) {
            // Enabled
            if(!daemonSymlink.getStat(tempStat).exists()) {
                daemonSymlink.symLink("../bin/tomcat").chown(
                    lsaUID,
                    lsgGID
                );
            }
        } else {
            // Disabled
            if(daemonSymlink.getStat(tempStat).exists()) daemonSymlink.delete();
        }

        // Start if needed
        if(needRestart && !sharedTomcat.isDisabled()) sharedTomcatsNeedingRestarted.add(sharedTomcat);
    }

    protected boolean upgradeSharedTomcatDirectory(UnixFile siteDirectory) throws IOException, SQLException {
        // TODO
        return false;
    }
}
