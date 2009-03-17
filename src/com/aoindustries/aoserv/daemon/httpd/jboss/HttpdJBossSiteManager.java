package com.aoindustries.aoserv.daemon.httpd.jboss;

/*
 * Copyright 2007-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages HttpdJBossSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdJBossSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

    /**
     * Gets the specific manager for one type of web site.
     */
    public static HttpdJBossSiteManager<? extends TomcatCommon> getInstance(HttpdJBossSite jbossSite) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();
        String jbossVersion = jbossSite.getHttpdJBossVersion().getTechnologyVersion(connector).getVersion();
        if(jbossVersion.equals("2.2.2")) return new HttpdJBossSiteManager_2_2_2(jbossSite);
        throw new SQLException("Unsupported version of standard JBoss: "+jbossVersion+" on "+jbossSite);
    }

    final protected HttpdJBossSite jbossSite;
    
    HttpdJBossSiteManager(HttpdJBossSite jbossSite) throws SQLException, IOException {
        super(jbossSite.getHttpdTomcatSite());
        this.jbossSite = jbossSite;
    }

    public UnixFile getPidFile() throws IOException, SQLException {
        return new UnixFile(
            HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
            + "/"
            + httpdSite.getSiteName()
            + "/var/run/jboss.pid"
        );
    }

    public boolean isStartable() throws IOException, SQLException {
        return httpdSite.getDisableLog()==null;
    }
    
    public String getStartStopScriptPath() throws IOException, SQLException {
        return
            HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
            + "/"
            + httpdSite.getSiteName()
            + "/bin/jboss"
        ;
    }

    public String getStartStopScriptUsername() throws IOException, SQLException {
        return httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername();
    }

    protected void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) {
        sitesNeedingRestarted.add(httpdSite);
    }
}
