package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdTomcatSharedSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdTomcatSharedSiteManager_3_2_4 extends HttpdTomcatSharedSiteManager {

    public HttpdTomcatSharedSiteManager_3_2_4(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}
