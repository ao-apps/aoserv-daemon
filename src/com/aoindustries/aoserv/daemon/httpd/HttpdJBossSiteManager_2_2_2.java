package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdJBossSite version 2.2.2 configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdJBossSiteManager_2_2_2 extends HttpdJBossSiteManager {

    public HttpdJBossSiteManager_2_2_2(HttpdJBossSite jbossSite) {
        super(jbossSite);
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}
