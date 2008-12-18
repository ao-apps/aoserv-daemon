package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatSharedSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_6_0_X extends HttpdTomcatSharedSiteManager<TomcatCommon_6_0_X> {

    HttpdTomcatSharedSiteManager_6_0_X(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 6.0.X
     */
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        throw new RuntimeException("TODO: Implement method");
    }

    public TomcatCommon_6_0_X getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }

    protected boolean rebuildConfigFiles(UnixFile siteDirectory) {
        throw new RuntimeException("TODO: Implement method");
    }

    protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // TODO
        return false;
    }
}
