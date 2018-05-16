/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

/**
 * Escapes arbitrary values for use in Apache directives.
 *
 * @author  AO Industries, Inc.
 */
class ApacheEscape {

	/**
	 * Escapes arbitrary text to be used in an Apache directive.
	 * Adds double quotes as-needed.  Please note that '$' is also
	 * escaped, so this might not be appropriate in <code>RewriteRule</code>
	 * or <code>${variable}</code> substitutions.
	 * <p>
	 * Please note, the dollar escaping relies on Apache being configured with
	 * <code>Define $ $</code>, as it is performed with a <code>${$}</code> hack.
	 * This is set in the aoserv-httpd-config package, in <code>core.inc</code>.
	 * </p>
	 *
	 * @see  #escape(java.lang.String, boolean)
	 */
	static String escape(String value) {
		return escape(value, true);
	}

	/**
	 * Escapes arbitrary text to be used in an Apache directive.
	 * Adds double quotes as-needed.  Optionally allowing '$' unescaped.
	 * <p>
	 * Please note, the dollar escaping relies on Apache being configured with
	 * <code>Define $ $</code>, as it is performed with a <code>${$}</code> hack.
	 * This is set in the aoserv-httpd-config package, in <code>core.inc</code>.
	 * </p>
	 * <p>
	 * I am unable to find clear documentation on the full set of rules for escaping Apache directives.
	 * I have experimented with various values and techniques to achieve this implementation.
	 * It seems there is no useful way to encode completely arbitrary values into directives.
	 * Thus, this set of rules may not be optimal (may perform more escaping than necessary) or,
	 * even worse, could be incomplete.
	 * </p>
	 *
	 * @return  the escaped string or the original string when no escaping required
	 *
	 * @see  #escape(java.lang.String)
	 */
	static String escape(String value, boolean escapeDollar) {
		int len = value.length();
		StringBuilder sb = null; // Created when first needed
		boolean quoted = false;
		for(int i = 0; i < len; i++) {
			char ch = value.charAt(i);
			// Characters not ever allowed
			if(ch == 0) throw new IllegalArgumentException("Null character not allowed in Apache directives");
			if(ch == '\b') throw new IllegalArgumentException("Backspace character not allowed in Apache directives");
			if(ch == '\f') throw new IllegalArgumentException("Form feed character not allowed in Apache directives");
			if(ch == '\n') throw new IllegalArgumentException("Newline character not allowed in Apache directives");
			if(ch == '\r') throw new IllegalArgumentException("Carriage return character not allowed in Apache directives");
			// Characters allowed, but only inside double-quoted strings
			if(
				ch == ' '
				|| ch == '\t'
				|| ch == '\''
				|| ch == '<'
				|| ch == '>'
			) {
				if(sb == null) sb = new StringBuilder(len * 2).append(value, 0, i);
				if(!quoted) {
					sb.insert(0, '"');
					quoted = true;
				}
				sb.append(ch);
			}
			// Do not allow control characters
			else if(ch < ' ') {
				throw new IllegalArgumentException("Control character not allowed in Apache directives: " + (int)ch);
			}
			// Escape "$" when dollar escaping enabled and followed by '{'
			else if(
				ch == '$'
				&& escapeDollar
				&& i < (len - 1)
				&& value.charAt(i + 1) == '{'
			) {
				if(sb == null) sb = new StringBuilder(len * 2).append(value, 0, i);
				sb.append("${$}"); // Relies on "Define $ $" set in configuration files.
			}
			// Characters that are backslash-escaped, enabled double-quoting
			else if(
				ch == '"'
				|| ch == '\\'
			) {
				if(sb == null) sb = new StringBuilder(len * 2).append(value, 0, i);
				if(!quoted) {
					sb.insert(0, '"');
					quoted = true;
				}
				sb.append('\\').append(ch);
			}
			// All other characters unaltered
			else {
				if(sb != null) sb.append(ch);
			}
		}
		if(sb == null) {
			assert !quoted;
			return value;
		} else {
			if(quoted) sb.append('"');
			return sb.toString();
		}
	}

	private ApacheEscape() {}
}
