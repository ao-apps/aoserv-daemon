package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages HttpdTomcatSharedSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_6_0_X extends HttpdTomcatSharedSiteManager {

    HttpdTomcatSharedSiteManager_6_0_X(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 6.0.X
     */
    protected void buildSiteDirectory(UnixFile siteDirectory, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        throw new RuntimeException("TODO: Implement method");
    }

    public TomcatCommon getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }
}
