package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdTomcatSharedSite version 4.1.X configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdTomcatSharedSiteManager_4_1_X extends HttpdTomcatSharedSiteManager {

    public HttpdTomcatSharedSiteManager_4_1_X(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}
