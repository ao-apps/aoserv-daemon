/*
 * Copyright 2007-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.client.web.tomcat.ContextParameter;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages PrivateTomcatSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_6_0_X extends HttpdTomcatStdSiteManager<TomcatCommon_6_0_X> {

	HttpdTomcatStdSiteManager_6_0_X(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	@Override
	protected void buildSiteDirectoryContents(String optSlash, UnixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
		if(isUpgrade) throw new IllegalArgumentException("In-place upgrade not supported");
		// Resolve and allocate stuff used throughout the method
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final String siteDir = siteDirectory.getPath();
		final UserServer lsa = httpdSite.getLinuxServerAccount();
		final int uid = lsa.getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();
		final Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		DaemonFileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/conf/Catalina", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
		if (!httpdSite.isDisabled()) DaemonFileUtils.ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/temp", 0770, uid, gid);
		DaemonFileUtils.ln("var/log", siteDir+"/logs", uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var/run", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE, 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/work", 0750, uid, gid);
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/bootstrap.jar", siteDir+"/bin/bootstrap.jar", uid, gid);
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/catalina.sh", siteDir+"/bin/catalina.sh", uid, gid);
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/commons-daemon.jar", siteDir+"/bin/commons-daemon.jar", uid, gid);
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/digest.sh", siteDir+"/bin/digest.sh", uid, gid);

		/*
		 * Set up the bash profile source
		 */
		String profileFile=siteDir+"/bin/profile";
		LinuxAccountManager.setBashProfile(lsa, profileFile);
		ChainWriter out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(profileFile).getSecureOutputStream(
					uid,
					gid,
					0750,
					false,
					uid_min,
					gid_min
				)
			)
		);
		try {
			out.print("#!/bin/sh\n"
					+ "\n"
					+ ". /etc/profile\n"
					+ ". ").print(osConfig.getDefaultJdkProfileSh()).print('\n');
			//if(enablePhp()) {
			//	out.print(". /opt/php-").print(httpdConfig.getDefaultPhpMinorVersion()).print("-i686/setenv.sh\n");
			//}
			//if(postgresServerMinorVersion!=null) {
			//	out.print(". /opt/postgresql-"+postgresServerMinorVersion+"-i686/setenv.sh\n");
			//}
			//out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
			out.print("\n"
					+ "umask 002\n"
					+ "\n"
					+ "export CATALINA_HOME=\"").print(siteDir).print("\"\n"
					+ "export CATALINA_BASE=\"").print(siteDir).print("\"\n"
					+ "export CATALINA_TEMP=\"").print(siteDir).print("/temp\"\n"
					+ "\n"
					+ "export PATH=\"${PATH}:").print(siteDir).print("/bin\"\n"
					+ "\n"
					+ "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n");
		} finally {
			out.close();
		}

		/*
		 * Write the bin/tomcat script.
		 */
		String tomcatScript=siteDir+"/bin/tomcat";
		out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(tomcatScript).getSecureOutputStream(
					uid,
					gid,
					0700,
					false,
					uid_min,
					gid_min
				)
			)
		);
		try {
			out.print("#!/bin/sh\n"
					+ "\n"
					+ "TOMCAT_HOME=\"").print(siteDir).print("\"\n"
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
					+ "        cd \"$TOMCAT_HOME\"\n"
					+ "        . \"$TOMCAT_HOME/bin/profile\"\n"
					+ "        umask 002\n"
					+ "        \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
					+ "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
					+ "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
					+ "    fi\n"
					+ "elif [ \"$1\" = \"daemon\" ]; then\n"
					+ "    cd \"$TOMCAT_HOME\"\n"
					+ "    . \"$TOMCAT_HOME/bin/profile\"\n"
					+ "\n"
					+ "    while [ 1 ]; do\n"
					+ "        umask 002\n"
					+ "        mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
					+ "        \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
					+ "        echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
					+ "        wait\n"
					+ "        RETCODE=\"$?\"\n"
					+ "        echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
					+ "        sleep 5\n"
					+ "    done\n"
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
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/setclasspath.sh", siteDir+"/bin/setclasspath.sh", uid, gid);

		out=new ChainWriter(new UnixFile(siteDir+"/bin/shutdown.sh").getSecureOutputStream(uid, gid, 0700, true, uid_min, gid_min));
		try {
			out.print("#!/bin/sh\n"
					+ "exec \"").print(siteDir).print("/bin/tomcat\" stop\n");
		} finally {
			out.close();
		}

		out=new ChainWriter(new UnixFile(siteDir+"/bin/startup.sh").getSecureOutputStream(uid, gid, 0700, true, uid_min, gid_min));
		try {
			out.print("#!/bin/sh\n"
					+ "exec \"").print(siteDir).print("/bin/tomcat\" start\n");
		} finally {
			out.close();
		}

		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/tomcat-juli.jar", siteDir+"/bin/tomcat-juli.jar", uid, gid);
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/tool-wrapper.sh", siteDir+"/bin/tool-wrapper.sh", uid, gid);
		DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/version.sh", siteDir+"/bin/version.sh", uid, gid);

		// Create the lib directory and all contents
		DaemonFileUtils.mkdir(siteDir+"/lib", 0770, uid, gid);
		DaemonFileUtils.lnAll("../" + optSlash + "apache-tomcat-6.0/lib/", siteDir+"/lib/", uid, gid);

		//if(postgresServerMinorVersion!=null) {
		//    String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
		//    if(postgresPath!=null) FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", siteDir+"/lib/postgresql.jar", uid, gid);
		//}
		//String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
		//if(mysqlConnectorPath!=null) {
		//    String filename = new UnixFile(mysqlConnectorPath).getFile().getName();
		//    FileUtils.ln("../../.."+mysqlConnectorPath, siteDir+"/lib/"+filename, uid, gid);
		//}

		/*
		 * Write the conf/catalina.policy file
		 */
		{
			UnixFile cp=new UnixFile(siteDir+"/conf/catalina.policy");
			new UnixFile("/opt/apache-tomcat-6.0/conf/catalina.policy").copyTo(cp, false);
			cp.chown(uid, gid).setMode(0660);
		}

		{
			UnixFile cp=new UnixFile(siteDir+"/conf/catalina.properties");
			new UnixFile("/opt/apache-tomcat-6.0/conf/catalina.properties").copyTo(cp, false);
			cp.chown(uid, gid).setMode(0660);
		}

		{
			UnixFile cp=new UnixFile(siteDir+"/conf/context.xml");
			new UnixFile("/opt/apache-tomcat-6.0/conf/context.xml").copyTo(cp, false);
			cp.chown(uid, gid).setMode(0660);
		}

		{
			UnixFile cp=new UnixFile(siteDir+"/conf/logging.properties");
			new UnixFile("/opt/apache-tomcat-6.0/conf/logging.properties").copyTo(cp, false);
			cp.chown(uid, gid).setMode(0660);
		}
		{
			UnixFile tu=new UnixFile(siteDir+"/conf/tomcat-users.xml");
			new UnixFile("/opt/apache-tomcat-6.0/conf/tomcat-users.xml").copyTo(tu, false);
			tu.chown(uid, gid).setMode(0660);
		}
		{
			UnixFile wx=new UnixFile(siteDir+"/conf/web.xml");
			new UnixFile("/opt/apache-tomcat-6.0/conf/web.xml").copyTo(wx, false);
			wx.chown(uid, gid).setMode(0660);
		}

		/*
		 * Write the ROOT/WEB-INF/web.xml file.
		 */
		String webXML=siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/web.xml";
		out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false, uid_min, gid_min)
			)
		);
		try {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
					+ "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\"\n"
					+ "   xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
					+ "   xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd\"\n"
					+ "   version=\"2.5\">\n"
					+ "  <display-name>Welcome to Tomcat</display-name>\n"
					+ "  <description>\n"
					+ "     Welcome to Tomcat\n"
					+ "  </description>\n"
					+ "</web-app>\n");
		} finally {
			out.close();
		}
	}

	@Override
	public TomcatCommon_6_0_X getTomcatCommon() {
		return TomcatCommon_6_0_X.getInstance();
	}

	@Override
	protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
		final TomcatCommon_6_0_X tomcatCommon = getTomcatCommon();
		AOServConnector conn = AOServDaemon.getConnector();

		// Build to RAM first
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(bout)) {
			List<Worker> hws=tomcatSite.getHttpdWorkers();
			if(hws.size()!=1) throw new SQLException("Expected to only find one Worker for PrivateTomcatSite #"+httpdSite.getPkey()+", found "+hws.size());
			Worker hw=hws.get(0);
			String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
			if(!hwProtocol.equals(JkProtocol.AJP13)) {
				throw new SQLException("Worker #"+hw.getPkey()+" for PrivateTomcatSite #"+httpdSite.getPkey()+" must be AJP13 but it is "+hwProtocol);
			}
			if(!httpdSite.isManual()) out.print(autoWarning);
			Bind shutdownPort=tomcatStdSite.getTomcat4ShutdownPort();
			if(shutdownPort==null) throw new SQLException("Unable to find shutdown port for PrivateTomcatSite="+tomcatStdSite);
			String shutdownKey=tomcatStdSite.getTomcat4ShutdownKey();
			if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for PrivateTomcatSite="+tomcatStdSite);
			out.print("<Server port=\"").encodeXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").encodeXmlAttribute(shutdownKey).print("\">\n"
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
					+ "      port=\"").encodeXmlAttribute(hw.getBind().getPort().getPort()).print("\"\n"
					+ "      address=\"").encodeXmlAttribute(IpAddress.LOOPBACK_IP).print("\"\n"
					+ "      maxPostSize=\"").encodeXmlAttribute(tomcatStdSite.getMaxPostSize()).print("\"\n"
					+ "      protocol=\"AJP/1.3\"\n"
					+ "      redirectPort=\"8443\"\n");
			// Do not include when is default "true"
			if(!tomcatStdSite.getTomcatAuthentication()) {
				out.print("      tomcatAuthentication=\"false\"\n");
			}
			out.print("    />\n"
					+ "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
					+ "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
					+ "             resourceName=\"UserDatabase\" />\"\n"
					+ "      <Host\n"
					+ "        name=\"localhost\"\n"
					+ "        appBase=\"webapps\"\n"
					+ "        unpackWARs=\"").encodeXmlAttribute(tomcatStdSite.getUnpackWARs()).print("\"\n"
					+ "        autoDeploy=\"").encodeXmlAttribute(tomcatStdSite.getAutoDeploy()).print("\"\n"
					+ "        xmlValidation=\"false\"\n"
					+ "        xmlNamespaceAware=\"false\"\n"
					+ "      >\n");
			for(Context htc : tomcatSite.getHttpdTomcatContexts()) {
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
				List<ContextParameter> parameters=htc.getHttpdTomcatParameters();
				List<ContextDataSource> dataSources=htc.getHttpdTomcatDataSources();
				if(parameters.isEmpty() && dataSources.isEmpty()) {
					out.print("        />\n");
				} else {
					out.print("        >\n");
					// Parameters
					for(ContextParameter parameter : parameters) {
						tomcatCommon.writeHttpdTomcatParameter(parameter, out);
					}
					// Data Sources
					for(ContextDataSource dataSource : dataSources) {
						tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
					}
					out.print("        </Context>\n");
				}
				if(!htc.isServerXmlConfigured()) out.print("        -->\n");
			}
			out.print("      </Host>\n"
					+ "    </Engine>\n"
					+ "  </Service>\n"
					+ "</Server>\n");
		}
		return bout.toByteArray();
	}

	@Override
	protected boolean upgradeSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// The only thing that needs to be modified is the included Tomcat
		return getTomcatCommon().upgradeTomcatDirectory(
			optSlash,
			siteDirectory,
			httpdSite.getLinuxServerAccount().getUid().getId(),
			httpdSite.getLinuxServerGroup().getGid().getId()
		);
	}

	/**
	 * Does not use any README.txt for change detection.
	 */
	@Override
	protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException, SQLException {
		return null;
	}
}
