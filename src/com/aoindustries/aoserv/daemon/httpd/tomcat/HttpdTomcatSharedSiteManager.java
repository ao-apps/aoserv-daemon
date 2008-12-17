package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatSharedSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager extends HttpdTomcatSiteManager {

    /**
     * Gets the specific manager for one type of web site.
     */
    static HttpdTomcatSharedSiteManager getInstance(HttpdTomcatSharedSite shrSite) throws IOException, SQLException {
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
}
