package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatStdSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_6_0_X extends HttpdTomcatStdSiteManager<TomcatCommon_6_0_X> {

    HttpdTomcatStdSiteManager_6_0_X(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    /**
     * Builds a standard install for Tomcat 6.0.X
     */
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        throw new RuntimeException("TODO: Implement method");
    }

    public TomcatCommon_6_0_X getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }

    protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
        throw new RuntimeException("TODO: Implement method");
    }

    protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // TODO
        return false;
    }
}
