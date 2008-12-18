package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages HttpdTomcatSharedSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

    /**
     * Gets the specific manager for one type of web site.
     */
    static HttpdTomcatSharedSiteManager<? extends TomcatCommon> getInstance(HttpdTomcatSharedSite shrSite) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();

        HttpdTomcatVersion htv=shrSite.getHttpdTomcatSite().getHttpdTomcatVersion();
        if(htv.isTomcat3_1(connector)) return new HttpdTomcatSharedSiteManager_3_1(shrSite);
        if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatSharedSiteManager_3_2_4(shrSite);
        if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatSharedSiteManager_4_1_X(shrSite);
        if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatSharedSiteManager_5_5_X(shrSite);
        if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatSharedSiteManager_6_0_X(shrSite);
        throw new SQLException("Unsupported version of shared Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+shrSite);
    }

    final protected HttpdTomcatSharedSite tomcatSharedSite;
    
    HttpdTomcatSharedSiteManager(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite.getHttpdTomcatSite());
        this.tomcatSharedSite = tomcatSharedSite;
    }

    /**
     * Worker is associated with the shared JVM.
     */
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        HttpdWorker hw = tomcatSharedSite.getHttpdSharedTomcat().getTomcat4Worker();
        if(hw==null) throw new SQLException("Unable to find shared HttpdWorker");
        return hw;
    }

    public UnixFile getPidFile() throws IOException, SQLException {
        return new UnixFile(
            HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
            + "/"
            + tomcatSharedSite.getHttpdSharedTomcat().getName()
            + "/var/run/tomcat.pid"
        );
    }

    public String getStartStopScriptPath() throws IOException, SQLException {
        return
            HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
            + "/"
            + tomcatSharedSite.getHttpdSharedTomcat().getName()
            + "/bin/tomcat"
        ;
    }

    protected void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) {
        sharedTomcatsNeedingRestarted.add(tomcatSharedSite.getHttpdSharedTomcat());
    }

    /**
     * Shared sites don't need to do anything to enable/disable.
     * The HttpdSharedTomcat manager will update the profile.sites file
     * and restart to take care of this.
     */
    protected void enableDisable(UnixFile siteDirectory) {
        // Do nothing
    }
}
