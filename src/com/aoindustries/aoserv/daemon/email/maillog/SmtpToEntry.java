package com.aoindustries.aoserv.daemon.email.maillog;

/*
 * Copyright 2002-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.util.*;

/**
 * Represents one line of the aillog file.
 *
 * @author  AO Industries, Inc.
 */
final public class SmtpToEntry extends SmtpEntry {

    /**
     * The mailer value for local delivery
     */
    public static final String LOCAL="local";

    private String to;
    private String ctladdr;
    private String mailer;
    private String relay;

    SmtpToEntry(Date date, String host, int pid, String messageID, String to, String ctladdr, String mailer, String relay) {
        super(date, host, pid, messageID);
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpToEntry.class, "<init>(Date,String,int,String,String,String,String,String)", null);
        try {
            this.to=to;
            this.ctladdr=ctladdr;
            this.mailer=mailer;
            this.relay=relay;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public String getTo() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpToEntry.class, "getTo()", null);
        try {
            return to;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getCtlAddr() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpToEntry.class, "getCtlAddr()", null);
        try {
            return ctladdr;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getMailer() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpToEntry.class, "getMailer()", null);
        try {
            return mailer;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getRelay() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpToEntry.class, "getRelay()", null);
        try {
            return relay;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String toString() {
        Profiler.startProfile(Profiler.FAST, SmtpToEntry.class, "toString()", null);
        try {
            return super.toString()+", to="+to+", ctladdr="+ctladdr+", mailer="+mailer+", relay="+relay;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}