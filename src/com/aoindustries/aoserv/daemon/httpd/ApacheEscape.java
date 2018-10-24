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
	 * escaped when found in form <code>${variable}</code>, so this might not be
	 * appropriate for <code>${variable}</code> substitutions.
	 * <p>
	 * Please note, the dollar escaping relies on Apache being configured with
	 * <code>Define &lt;dollarVariable&gt; $</code>, as it is performed with a <code>${dollarVariable}</code> hack.
	 * </p>
	 *
	 * @see  #escape(java.lang.String, java.lang.String, boolean)
	 */
	static String escape(String dollarVariable, String value) {
		return escape(dollarVariable, value, false);
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
	 * @see  #escape(java.lang.String, java.lang.String)
	 */
	static String escape(String dollarVariable, String value, boolean allowVariables) {
		int len = value.length();
		// Empty string as ""
		if(len == 0) return "\"\"";
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
				&& !allowVariables
				&& i < (len - 1)
				&& value.charAt(i + 1) == '{'
			) {
				// Find name of variable
				int endPos = value.indexOf('}', i + 2);
				if(
					// No closing } found, no escape needed
					endPos == -1
					// Empty variable name, no escape needed
					|| endPos == (i + 2)
				) {
					if(sb != null) sb.append('$');
				} else {
					int colonPos = value.indexOf(':', i + 2);
					if(colonPos != -1 && colonPos < endPos) {
						// Colon in name, don't escape variable
						if(sb != null) sb.append('$');
					} else {
						if(dollarVariable == null) {
							throw new IllegalArgumentException("Unable to escape \"${\", no dollarVariable: " + value);
						}
						if(sb == null) sb = new StringBuilder(len * 2).append(value, 0, i);
						sb.append("${").append(dollarVariable).append('}'); // Relies on "Define <dollarVariable> $" set in configuration files.
					}
				}
			}
			// Backslashes are only escaped when followed by another backslash, a double quote, or
			// are at the end of the value.  Furthermore, when at the end, the value is double-quoted
			// to avoid possible line continuation
			else if(ch == '\\') {
				if(i == (len - 1)) {
					// Is the last character, double-quote and escape
					if(sb == null) sb = new StringBuilder(len * 2).append(value, 0, i);
					if(!quoted) {
						sb.insert(0, '"');
						quoted = true;
					}
					sb.append("\\\\");
				} else {
					char next = value.charAt(i + 1);
					if(next == '\\' || next == '"') {
						// Followed by \ or ", escape only
						if(sb == null) sb = new StringBuilder(len * 2).append(value, 0, i);
						sb.append("\\\\");
					} else {
						// No escape required
						if(sb != null) sb.append(ch);
					}
				}
			}
			// Characters that are backslash-escaped, enables double-quoting
			else if(ch == '"') {
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
