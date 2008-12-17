package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */

/**
 * Some common code for Tomcat 3.1
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_3_1 extends TomcatCommon_3_X {

    private static final TomcatCommon_3_1 instance = new TomcatCommon_3_1();
    static TomcatCommon_3_1 getInstance() {
        return instance;
    }

    private TomcatCommon_3_1() {}
}
