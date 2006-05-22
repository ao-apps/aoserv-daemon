package com.aoindustries.aoserv.daemon.email.maillog;

/*
 * Copyright 2002-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.util.*;

/**
 * Represents one line of the maillog file.
 *
 * @author  AO Industries, Inc.
 */
final public class SmtpFromEntry extends SmtpEntry {

    private String from;
    private int size;
    private String relay;

    SmtpFromEntry(Date date, String host, int pid, String messageID, String from, int size, String relay) {
        super(date, host, pid, messageID);
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpFromEntry.class, "<init>(Date,String,int,String,String,int,String)", null);
        try {
            this.from=from;
            this.size=size;
            this.relay=relay;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public String getFrom() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpFromEntry.class, "getFrom()", null);
        try {
            return from;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public int getSize() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpFromEntry.class, "getSize()", null);
        try {
            return size;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getRelay() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpFromEntry.class, "getRelay()", null);
        try {
            return relay;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String toString() {
        Profiler.startProfile(Profiler.FAST, SmtpFromEntry.class, "toString()", null);
        try {
            return super.toString()+", from="+from+", size="+size+", relay="+relay;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}