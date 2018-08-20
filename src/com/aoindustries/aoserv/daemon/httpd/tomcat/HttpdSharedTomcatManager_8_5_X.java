/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

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
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.DomainName;
import com.aoindustries.util.SortedArrayList;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages HttpdSharedTomcat version 8.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_8_5_X extends VersionedSharedTomcatManager<TomcatCommon_8_5_X> {

	HttpdSharedTomcatManager_8_5_X(HttpdSharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_8_5_X getTomcatCommon() {
		return TomcatCommon_8_5_X.getInstance();
	}

	@Override
	protected String getApacheTomcatDir() {
		return "apache-tomcat-8.5";
	}

	@Override
	protected void writeServerXml(
		ChainWriter out,
		HttpdSharedTomcat sharedTomcat,
		List<HttpdTomcatSharedSite> sites
	) throws IOException, SQLException {
		final TomcatCommon tomcatCommon = getTomcatCommon();
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
		final UnixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
		String autoWarning = getAutoWarningXml();

		HttpdWorker hw = sharedTomcat.getTomcat4Worker();
		if(!sharedTomcat.isManual()) out.print(autoWarning);
		NetBind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
		if(shutdownPort == null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: " + sharedTomcat);
		String shutdownKey = sharedTomcat.getTomcat4ShutdownKey();
		if(shutdownKey == null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: " + sharedTomcat);
		out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<Server port=\"").encodeXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").encodeXmlAttribute(shutdownKey).print("\">\n"
				+ "  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n"
				+ "  <!-- Security listener. Documentation at /docs/config/listeners.html\n"
				+ "  <Listener className=\"org.apache.catalina.security.SecurityListener\" />\n"
				+ "  -->\n"
				+ "  <!--APR library loader. Documentation at /docs/apr.html -->\n"
				+ "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n"
				+ "  <!-- Prevent memory leaks due to use of particular java/javax APIs-->\n"
				+ "  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n"
				+ "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
				+ "  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n"
				+ "\n"
				+ "  <!-- Global JNDI resources\n"
				+ "       Documentation at /docs/jndi-resources-howto.html\n"
				+ "  -->\n"
				+ "  <GlobalNamingResources>\n"
				+ "    <!-- Editable user database that can also be used by\n"
				+ "         UserDatabaseRealm to authenticate users\n"
				+ "    -->\n"
				+ "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
				+ "              type=\"org.apache.catalina.UserDatabase\"\n"
				+ "              description=\"User database that can be updated and saved\"\n"
				+ "              factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
				+ "              pathname=\"conf/tomcat-users.xml\" />\n"
				+ "  </GlobalNamingResources>\n"
				+ "\n"
				+ "  <Service name=\"Catalina\">\n"
				+ "    <Connector\n"
				+ "      port=\"").encodeXmlAttribute(hw.getNetBind().getPort().getPort()).print("\"\n"
				+ "      address=\"").encodeXmlAttribute(IPAddress.LOOPBACK_IP).print("\"\n"
				+ "      maxPostSize=\"").encodeXmlAttribute(sharedTomcat.getMaxPostSize()).print("\"\n"
				+ "      protocol=\"AJP/1.3\"\n"
				+ "      redirectPort=\"8443\"\n"
				+ "      URIEncoding=\"UTF-8\"\n"
				+ "    />\n"
				+ "\n"
				+ "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
				+ "      <!-- Use the LockOutRealm to prevent attempts to guess user passwords\n"
				+ "           via a brute-force attack -->\n"
				+ "      <Realm className=\"org.apache.catalina.realm.LockOutRealm\">\n"
				+ "        <!-- This Realm uses the UserDatabase configured in the global JNDI\n"
				+ "             resources under the key \"UserDatabase\".  Any edits\n"
				+ "             that are performed against this UserDatabase are immediately\n"
				+ "             available for use by the Realm.  -->"
				+ "        <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
				+ "               resourceName=\"UserDatabase\"/>\n"
				+ "      </Realm>\n");
		for (HttpdTomcatSharedSite site : sites) {
			HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
			if(!hs.isDisabled()) {
				DomainName primaryHostname = hs.getPrimaryHttpdSiteURL().getHostname();
				out.print("\n"
						+ "      <Host\n"
						+ "        name=\"").encodeXmlAttribute(primaryHostname.toString()).print("\"\n"
						+ "        appBase=\"").encodeXmlAttribute(wwwDirectory).print('/').encodeXmlAttribute(hs.getSiteName()).print("/webapps\"\n"
						+ "        unpackWARs=\"").encodeXmlAttribute(sharedTomcat.getUnpackWARs()).print("\"\n"
						+ "        autoDeploy=\"").encodeXmlAttribute(sharedTomcat.getAutoDeploy()).print("\"\n"
						+ "      >\n");
				List<String> usedHostnames = new SortedArrayList<>();
				usedHostnames.add(primaryHostname.toString());
				List<HttpdSiteBind> binds = hs.getHttpdSiteBinds();
				for (HttpdSiteBind bind : binds) {
					for (HttpdSiteURL url : bind.getHttpdSiteURLs()) {
						DomainName hostname = url.getHostname();
						if(!usedHostnames.contains(hostname.toString())) {
							out.print("        <Alias>").encodeXhtml(hostname).print("</Alias>\n");
							usedHostnames.add(hostname.toString());
						}
					}
					// When listed first, also include the IP addresses as aliases
					if(hs.listFirst()) {
						String ip = bind.getHttpdBind().getNetBind().getIPAddress().getInetAddress().toString();
						if(!usedHostnames.contains(ip)) {
							out.print("        <Alias>").encodeXhtml(ip).print("</Alias>\n");
							usedHostnames.add(ip);
						}
					}
				}
				HttpdTomcatSite tomcatSite = hs.getHttpdTomcatSite();
				for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
					if(!htc.isServerXmlConfigured()) out.print("        <!--\n");
					out.print("        <Context\n");
					if(htc.getClassName() != null) out.print("          className=\"").encodeXmlAttribute(htc.getClassName()).print("\"\n");
					out.print("          cookies=\"").encodeXmlAttribute(htc.useCookies()).print("\"\n"
							+ "          crossContext=\"").encodeXmlAttribute(htc.allowCrossContext()).print("\"\n"
							+ "          docBase=\"").encodeXmlAttribute(htc.getDocBase()).print("\"\n"
							+ "          override=\"").encodeXmlAttribute(htc.allowOverride()).print("\"\n"
							+ "          path=\"").encodeXmlAttribute(htc.getPath()).print("\"\n"
							+ "          privileged=\"").encodeXmlAttribute(htc.isPrivileged()).print("\"\n"
							+ "          reloadable=\"").encodeXmlAttribute(htc.isReloadable()).print("\"\n"
							+ "          useNaming=\"").encodeXmlAttribute(htc.useNaming()).print("\"\n");
					if(htc.getWrapperClass() != null) out.print("          wrapperClass=\"").encodeXmlAttribute(htc.getWrapperClass()).print("\"\n");
					out.print("          debug=\"").encodeXmlAttribute(htc.getDebugLevel()).print("\"\n");
					if(htc.getWorkDir() != null) out.print("          workDir=\"").encodeXmlAttribute(htc.getWorkDir()).print("\"\n");
					List<HttpdTomcatParameter> parameters = htc.getHttpdTomcatParameters();
					List<HttpdTomcatDataSource> dataSources = htc.getHttpdTomcatDataSources();
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
