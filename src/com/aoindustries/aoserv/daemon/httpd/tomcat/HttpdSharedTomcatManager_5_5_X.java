/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
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
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages HttpdSharedTomcat version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_5_5_X extends HttpdSharedTomcatManager<TomcatCommon_5_5_X> {

	private static final Logger logger = Logger.getLogger(HttpdSharedTomcatManager_5_5_X.class.getName());

	HttpdSharedTomcatManager_5_5_X(HttpdSharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_5_5_X getTomcatCommon() {
		return TomcatCommon_5_5_X.getInstance();
	}

	@Override
	void buildSharedTomcatDirectory(String optSlash, UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
		/*
		 * Get values used in the rest of the loop.
		 */
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
		final AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		final TomcatCommon_5_5_X tomcatCommon = getTomcatCommon();
		final LinuxServerAccount lsa = sharedTomcat.getLinuxServerAccount();
		final int lsaUID = lsa.getUid().getId();
		final LinuxServerGroup lsg = sharedTomcat.getLinuxServerGroup();
		final int lsgGID = lsg.getGid().getId();
		final String wwwGroupDir = sharedTomcatDirectory.getPath();
		final UnixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
		final UnixFile daemonUF = new UnixFile(sharedTomcatDirectory, "daemon", false);

		// Create and fill in the directory if it does not exist or is owned by root.
		UnixFile workUF = new UnixFile(sharedTomcatDirectory, "work", false);
		UnixFile innerWorkUF = new UnixFile(workUF, "Tomcat-Apache", false);

		boolean needRestart=false;
		Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
		if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == UnixFile.ROOT_GID) {

			// Create the /wwwgroup/name/...

			// 001
			if (!sharedTomcatStat.exists()) sharedTomcatDirectory.mkdir();
			sharedTomcatDirectory.setMode(0770);
			new UnixFile(sharedTomcatDirectory, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(sharedTomcatDirectory, "conf", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
			DaemonFileUtils.ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/temp", 0770, lsaUID, lsgGID);
			UnixFile varUF = new UnixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

			workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
			DaemonFileUtils.mkdir(innerWorkUF.getPath(), 0750, lsaUID, lsgGID);

			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/commons-logging-api.jar", wwwGroupDir+"/bin/commons-logging-api.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/digest.sh", wwwGroupDir+"/bin/digest.sh", lsaUID, lsgGID);

			String profileFile = wwwGroupDir + "/bin/profile";
			LinuxAccountManager.setBashProfile(lsa, profileFile);

			UnixFile profileUF = new UnixFile(profileFile);
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false, uid_min, gid_min)
				)
			);
			try {
				out.print("#!/bin/sh\n"
						+ "\n"
						+ ". /etc/profile\n"
						+ ". ").print(osConfig.getJdk17ProfileSh()).print('\n');
				out.print("\n"
						+ "umask 002\n"
						+ "\n"
						+ "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
						+ "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
						+ "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
						+ "\n"
						+ "export PATH=\"${PATH}:").print(wwwGroupDir).print("/bin\"\n"
						+ "\n"
						+ "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n"
						+ "\n"
						+ ". ").print(wwwGroupDir).print("/bin/profile.sites\n"
						+ "\n"
						+ "for SITE in $SITES\n"
						+ "do\n"
						+ "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
						+ "done\n");
			} finally {
				out.close();
			}

			// 004

			UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
			out=new ChainWriter(
				new BufferedOutputStream(
					tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false, uid_min, gid_min)
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
						+ "            mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
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

			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

			UnixFile shutdown=new UnixFile(wwwGroupDir+"/bin/shutdown.sh");
			out=new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true, uid_min, gid_min));
			try {
				out.print("#!/bin/sh\n"
						+ "exec \"").print(wwwGroupDir).print("/bin/tomcat\" stop\n");
			} finally {
				out.close();
			}

			UnixFile startup=new UnixFile(wwwGroupDir+"/bin/startup.sh");
			out=new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true, uid_min, gid_min));
			try {
				out.print("#!/bin/sh\n"
						+ "exec \"").print(wwwGroupDir).print("/bin/tomcat\" start\n");
			} finally {
				out.close();
			}

			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/tomcat-juli.jar", wwwGroupDir+"/bin/tomcat-juli.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-5.5/bin/version.sh", wwwGroupDir+"/bin/version.sh", lsaUID, lsgGID);

			// Create the common directory and all contents
			DaemonFileUtils.mkdir(wwwGroupDir+"/common", 0770, lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/common/classes", 0770, lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/common/endorsed", 0770, lsaUID, lsgGID);
			DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-5.5/common/endorsed/", wwwGroupDir+"/common/endorsed/", lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/common/i18n", 0770, lsaUID, lsgGID);
			DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-5.5/common/i18n/", wwwGroupDir+"/common/i18n/", lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/common/lib", 0770, lsaUID, lsgGID);
			DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-5.5/common/lib/", wwwGroupDir+"/common/lib/", lsaUID, lsgGID);

			// Write the conf/catalina.policy file
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.policy");
				new UnixFile("/opt/apache-tomcat-5.5/conf/catalina.policy").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.properties");
				new UnixFile("/opt/apache-tomcat-5.5/conf/catalina.properties").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/context.xml");
				new UnixFile("/opt/apache-tomcat-5.5/conf/context.xml").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/logging.properties");
				new UnixFile("/opt/apache-tomcat-5.5/conf/logging.properties").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}

			// Create the tomcat-users.xml file
			UnixFile tuUF=new UnixFile(wwwGroupDir+"/conf/tomcat-users.xml");
			new UnixFile("/opt/apache-tomcat-5.5/conf/tomcat-users.xml").copyTo(tuUF, false);
			tuUF.chown(lsaUID, lsgGID).setMode(0660);

			// Create the web.xml file.
			UnixFile webUF=new UnixFile(wwwGroupDir+"/conf/web.xml");
			new UnixFile("/opt/apache-tomcat-5.5/conf/web.xml").copyTo(webUF, false);
			webUF.chown(lsaUID, lsgGID).setMode(0660);

			DaemonFileUtils.mkdir(wwwGroupDir+"/server", 0770, lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/server/classes", 0770, lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/server/lib", 0770, lsaUID, lsgGID);
			DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-5.5/server/lib/", wwwGroupDir+"/server/lib/", lsaUID, lsgGID);

			DaemonFileUtils.mkdir(wwwGroupDir+"/server/webapps", 0770, lsaUID, lsgGID);

			// The shared directory
			DaemonFileUtils.mkdir(wwwGroupDir+"/shared", 0770, lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/shared/classes", 0770, lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/shared/lib", 0770, lsaUID, lsgGID);

			// Set the ownership to avoid future rebuilds of this directory
			sharedTomcatDirectory.chown(lsaUID, lsgGID);

			needRestart=true;
		}

		// always rebuild profile.sites file
		UnixFile newSitesFileUF = new UnixFile(sharedTomcatDirectory, "bin/profile.sites.new", false);
		ChainWriter out = new ChainWriter(
			new BufferedOutputStream(
				newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true, uid_min, gid_min)
			)
		);
		List<HttpdTomcatSharedSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
		try {
			out.print("export SITES=\"");
			boolean didOne=false;
			for(HttpdTomcatSharedSite site : sites) {
				HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
				if(!hs.isDisabled()) {
					if(didOne) out.print(' ');
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
		Stat sitesStat = sitesFile.getStat();
		if(!sitesStat.exists() || !newSitesFileUF.contentEquals(sitesFile)) {
			needRestart=true;
			if(sitesStat.exists()) {
				UnixFile backupFile=new UnixFile(sharedTomcatDirectory, "bin/profile.sites.old", false);
				sitesFile.renameTo(backupFile);
			}
			newSitesFileUF.renameTo(sitesFile);
		} else newSitesFileUF.delete();

		// make work directories and remove extra work dirs
		List<String> workFiles = new SortedArrayList<>();
		String[] wlist = innerWorkUF.getFile().list();
		if(wlist!=null) {
			workFiles.addAll(Arrays.asList(wlist));
		}
		for (HttpdTomcatSharedSite site : sites) {
			HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
			if(!hs.isDisabled()) {
				String subwork = hs.getPrimaryHttpdSiteURL().getHostname().toString();
				workFiles.remove(subwork);
				UnixFile workDir = new UnixFile(innerWorkUF, subwork, false);
				if (!workDir.getStat().exists()) {
					workDir
						.mkdir()
						.chown(
							lsaUID,
							hs.getLinuxServerGroup().getGid().getId()
						)
						.setMode(0750)
					;
				}
			}
		}
		for(String workFile : workFiles) {
			File toDelete = new File(innerWorkUF.getFile(), workFile);
			if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
			deleteFileList.add(toDelete);
		}

		// Rebuild the server.xml for Tomcat 4 and Tomcat 5 JVMs
		String autoWarning = getAutoWarningXml();
		String autoWarningOld = getAutoWarningXmlOld();
		String confServerXML=wwwGroupDir+"/conf/server.xml";
		UnixFile confServerXMLUF=new UnixFile(confServerXML);
		if(!sharedTomcat.isManual() || !confServerXMLUF.getStat().exists()) {
			String newConfServerXML=wwwGroupDir+"/conf/server.xml.new";
			UnixFile newConfServerXMLUF=new UnixFile(newConfServerXML);
			out=new ChainWriter(
				new BufferedOutputStream(
					newConfServerXMLUF.getSecureOutputStream(lsaUID, lsgGID, 0660, true, uid_min, gid_min)
				)
			);
			try {
				HttpdWorker hw=sharedTomcat.getTomcat4Worker();
				if(!sharedTomcat.isManual()) out.print(autoWarning);
				NetBind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
				if(shutdownPort==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+sharedTomcat);
				String shutdownKey=sharedTomcat.getTomcat4ShutdownKey();
				if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+sharedTomcat);
				out.print("<Server port=\"").encodeXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").encodeXmlAttribute(shutdownKey).print("\" debug=\"0\">\n");
				out.print("  <GlobalNamingResources>\n"
						+ "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
						+ "              type=\"org.apache.catalina.UserDatabase\"\n"
						+ "       description=\"User database that can be updated and saved\"\n"
						+ "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
						+ "          pathname=\"conf/tomcat-users.xml\" />\n"
						+ "   </GlobalNamingResources>\n"
						+ "  <Service name=\"Catalina\">\n"
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
						+ "      port=\"").encodeXmlAttribute(hw.getNetBind().getPort().getPort()).print("\"\n"
						+ "      enableLookups=\"false\"\n"
						+ "      minProcessors=\"2\"\n"
						+ "      maxProcessors=\"200\"\n"
						+ "      address=\"").encodeXmlAttribute(IPAddress.LOOPBACK_IP).print("\"\n"
						+ "      acceptCount=\"10\"\n"
						+ "      debug=\"0\"\n"
						+ "      maxPostSize=\"").encodeXmlAttribute(sharedTomcat.getMaxPostSize()).print("\"\n"
						+ "      protocol=\"AJP/1.3\"\n"
						+ "    />\n"
						+ "    <Engine name=\"Catalina\" defaultHost=\"localhost\" debug=\"0\">\n"
						+ "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
						+ "          resourceName=\"UserDatabase\" />\"\n");
				for (HttpdTomcatSharedSite site : sites) {
					HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
					if(!hs.isDisabled()) {
						String primaryHostname=hs.getPrimaryHttpdSiteURL().getHostname().toString();
						out.print("      <Host\n"
								+ "        name=\"").encodeXmlAttribute(primaryHostname).print("\"\n"
								+ "        debug=\"0\"\n"
								+ "        appBase=\"").encodeXmlAttribute(wwwDirectory).print('/').encodeXmlAttribute(hs.getSiteName()).print("/webapps\"\n"
								+ "        unpackWARs=\"").encodeXmlAttribute(sharedTomcat.getUnpackWARs()).print("\"\n"
								+ "        autoDeploy=\"").encodeXmlAttribute(sharedTomcat.getAutoDeploy()).print("\"\n"
								+ "        xmlValidation=\"false\"\n"
								+ "        xmlNamespaceAware=\"false\"\n"
								+ "      >\n");
						List<String> usedHostnames=new SortedArrayList<>();
						usedHostnames.add(primaryHostname);
						List<HttpdSiteBind> binds=hs.getHttpdSiteBinds();
						for (HttpdSiteBind bind : binds) {
							List<HttpdSiteURL> urls=bind.getHttpdSiteURLs();
							for (HttpdSiteURL url : urls) {
								String hostname = url.getHostname().toString();
								if(!usedHostnames.contains(hostname)) {
									out.print("        <Alias>").encodeXhtml(hostname).print("</Alias>\n");
									usedHostnames.add(hostname);
								}
							}
							// When listed first, also include the IP addresses as aliases
							if(hs.listFirst()) {
								String ip=bind.getHttpdBind().getNetBind().getIPAddress().getInetAddress().toString();
								if(!usedHostnames.contains(ip)) {
									out.print("        <Alias>").encodeXhtml(ip).print("</Alias>\n");
									usedHostnames.add(ip);
								}
							}
						}
						HttpdTomcatSite tomcatSite=hs.getHttpdTomcatSite();
						for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
							if(!htc.isServerXmlConfigured()) out.print("        <!--\n");
							out.print("        <Context\n");
							if(htc.getClassName()!=null) out.print("          className=\"").encodeXmlAttribute(htc.getClassName()).print("\"\n");
							out.print("          cookies=\"").encodeXmlAttribute(htc.useCookies()).print("\"\n"
									+ "          crossContext=\"").encodeXmlAttribute(htc.allowCrossContext()).print("\"\n"
									+ "          docBase=\"").encodeXmlAttribute(htc.getDocBase()).print("\"\n"
									+ "          override=\"").encodeXmlAttribute(htc.allowOverride()).print("\"\n"
									+ "          path=\"").encodeXmlAttribute(htc.getPath()).print("\"\n"
									+ "          privileged=\"").encodeXmlAttribute(htc.isPrivileged()).print("\"\n"
									+ "          reloadable=\"").encodeXmlAttribute(htc.isReloadable()).print("\"\n"
									+ "          useNaming=\"").encodeXmlAttribute(htc.useNaming()).print("\"\n");
							if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").encodeXmlAttribute(htc.getWrapperClass()).print("\"\n");
							out.print("          debug=\"").encodeXmlAttribute(htc.getDebugLevel()).print("\"\n");
							if(htc.getWorkDir()!=null) out.print("          workDir=\"").encodeXmlAttribute(htc.getWorkDir()).print("\"\n");
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
							if(!htc.isServerXmlConfigured()) out.print("        -->\n");
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
				!confServerXMLUF.getStat().exists()
				|| !newConfServerXMLUF.contentEquals(confServerXMLUF)
			) {
				needRestart=true;
				newConfServerXMLUF.renameTo(confServerXMLUF);
			} else newConfServerXMLUF.delete();
		} else {
			try {
				DaemonFileUtils.stripFilePrefix(
					confServerXMLUF,
					autoWarningOld,
					uid_min,
					gid_min
				);
				DaemonFileUtils.stripFilePrefix(
					confServerXMLUF,
					autoWarning,
					uid_min,
					gid_min
				);
			} catch(IOException err) {
				// Errors OK because this is done in manual mode and they might have symbolic linked stuff
			}
		}

		// Enable/Disable
		boolean hasEnabledSite = false;
		for(HttpdTomcatSharedSite htss : sharedTomcat.getHttpdTomcatSharedSites()) {
			if(!htss.getHttpdTomcatSite().getHttpdSite().isDisabled()) {
				hasEnabledSite = true;
				break;
			}
		}
		UnixFile daemonSymlink = new UnixFile(daemonUF, "tomcat", false);
		if(!sharedTomcat.isDisabled() && hasEnabledSite) {
			// Enabled
			if(!daemonSymlink.getStat().exists()) {
				daemonSymlink.symLink("../bin/tomcat").chown(
					lsaUID,
					lsgGID
				);
			}
			// Start if needed
			if(needRestart) sharedTomcatsNeedingRestarted.add(sharedTomcat);
		} else {
			// Disabled
			if(daemonSymlink.getStat().exists()) daemonSymlink.delete();
		}
	}

	@Override
	protected boolean upgradeSharedTomcatDirectory(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// Upgrade Tomcat
		boolean needsRestart = getTomcatCommon().upgradeTomcatDirectory(
			optSlash,
			siteDirectory,
			sharedTomcat.getLinuxServerAccount().getUid().getId(),
			sharedTomcat.getLinuxServerGroup().getGid().getId()
		);
		return needsRestart;
	}
}
