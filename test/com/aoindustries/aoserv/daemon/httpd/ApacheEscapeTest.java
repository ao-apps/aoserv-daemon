/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import static com.aoindustries.aoserv.daemon.httpd.ApacheEscape.escape;
import junit.framework.Assert;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * @author  AO Industries, Inc.
 */
public class ApacheEscapeTest {

	@Test
    public void testEmptyIsQuoted() {
		assertEquals("\"\"", escape(null, ""));
    }

	@Test(expected = IllegalArgumentException.class)
	public void testNullNotAllowedInsideVariable() {
		escape("$", "${test\0}");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testNullNotAllowedOutsideVariable() {
		escape("$", "${test}\0");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testBackspaceNotAllowedBeginning() {
		escape(null, "\bTest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testBackspaceNotAllowedMiddle() {
		escape(null, "Test\btest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testBackspaceNotAllowedEnd() {
		escape(null, "Test\b");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testFormFeedNotAllowedBeginning() {
		escape(null, "\fTest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testFormFeedNotAllowedMiddle() {
		escape(null, "Test\ftest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testFormFeedNotAllowedEnd() {
		escape(null, "Test\f");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testNewlineNotAllowedBeginning() {
		escape(null, "\nTest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testNewlineNotAllowedMiddle() {
		escape(null, "Test\ntest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testNewlineNotAllowedEnd() {
		escape(null, "Test\n");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testCarriageReturnNotAllowedBeginning() {
		escape(null, "\rTest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testCarriageReturnNotAllowedMiddle() {
		escape(null, "Test\rtest");
    }

	@Test(expected = IllegalArgumentException.class)
	public void testCarriageReturnNotAllowedEnd() {
		escape(null, "Test\r");
    }

	@Test
    public void testSpaceBeginning() {
		assertEquals("\" Test\"", escape(null, " Test"));
    }

	@Test
    public void testSpaceMiddle() {
		assertEquals("\"Test test\"", escape(null, "Test test"));
    }

	@Test
    public void testSpaceEnd() {
		assertEquals("\"Test \"", escape(null, "Test "));
    }

	@Test
    public void testTabBeginning() {
		assertEquals("\"\tTest\"", escape(null, "\tTest"));
    }

	@Test
    public void testTabMiddle() {
		assertEquals("\"Test\ttest\"", escape(null, "Test\ttest"));
    }

	@Test
    public void testTabEnd() {
		assertEquals("\"Test\t\"", escape(null, "Test\t"));
    }

	@Test
    public void testSingleQuoteBeginning() {
		assertEquals("\"'Test\"", escape(null, "'Test"));
    }

	@Test
    public void testSingleQuoteMiddle() {
		assertEquals("\"Test'test\"", escape(null, "Test\'test"));
    }

	@Test
    public void testSingleQuoteEnd() {
		assertEquals("\"Test'\"", escape(null, "Test'"));
    }

	@Test
    public void testLessThanBeginning() {
		assertEquals("\"<Test\"", escape(null, "<Test"));
    }

	@Test
    public void testLessThanMiddle() {
		assertEquals("\"Test<test\"", escape(null, "Test<test"));
    }

	@Test
    public void testLessThanEnd() {
		assertEquals("\"Test<\"", escape(null, "Test<"));
    }

	@Test
    public void testGreaterThanBeginning() {
		assertEquals("\">Test\"", escape(null, ">Test"));
    }

	@Test
    public void testGreaterThanMiddle() {
		assertEquals("\"Test>test\"", escape(null, "Test>test"));
    }

	@Test
    public void testGreaterThanEnd() {
		assertEquals("\"Test>\"", escape(null, "Test>"));
    }

	@Test
    public void testTabOnlyControlCharacterAllowed() {
		for(char ch = 0; ch < ' '; ch++) {
			if(ch != '\t') {
				try {
					escape(null, ch + "Test");
					Assert.fail("Control character allowed at beginning: " + (int)ch);
				} catch(IllegalArgumentException e) {
					// Expected
				}
				try {
					escape(null, "Test" + ch + "test");
					Assert.fail("Control character allowed in middle: " + (int)ch);
				} catch(IllegalArgumentException e) {
					// Expected
				}
				try {
					escape(null, "Test" + ch);
					Assert.fail("Control character allowed at end: " + (int)ch);
				} catch(IllegalArgumentException e) {
					// Expected
				}
			}
		}
    }

	@Test
    public void testNoEscapeDollarOnly() {
		assertEquals("$", escape("$", "$"));
    }

	@Test
    public void testNoEscapeDollarOnlyNoDollarVariable() {
		assertEquals("$", escape(null, "$"));
    }

	@Test
    public void testNoEscapeIncompleteStart() {
		assertEquals("${", escape("$", "${"));
    }

	@Test
    public void testNoEscapeIncompleteStartNoDollarVariable() {
		assertEquals("${", escape(null, "${"));
    }

	@Test
    public void testNoEscapeIncompleteVariable() {
		assertEquals("${test", escape("$", "${test"));
    }

	@Test
    public void testNoEscapeIncompleteVariableNoDollarVariable() {
		assertEquals("${test", escape(null, "${test"));
    }

	@Test
    public void testNoEscapeIncompleteVariableColon() {
		assertEquals("${test:", escape("$", "${test:"));
    }

	@Test
    public void testNoEscapeIncompleteVariableColonNoDollarVariable() {
		assertEquals("${test:", escape(null, "${test:"));
    }

	@Test
    public void testNoEscapeIncompleteVariableColonMore() {
		assertEquals("${test:more", escape("$", "${test:more"));
    }

	@Test
    public void testNoEscapeIncompleteVariableColonMoreNoDollarVariable() {
		assertEquals("${test:more", escape(null, "${test:more"));
    }

	@Test
    public void testNoEscapeEmptyVariable() {
		assertEquals("${}", escape("$", "${}"));
    }

	@Test
    public void testNoEscapeEmptyVariableNoDollarVariable() {
		assertEquals("${}", escape(null, "${}"));
    }

	@Test
    public void testNoEscapeColon() {
		assertEquals("${:}", escape("$", "${:}"));
    }

	@Test
    public void testNoEscapeColonNoDollarVariable() {
		assertEquals("${:}", escape(null, "${:}"));
    }

	@Test
    public void testNoEscapeVariableColon() {
		assertEquals("${test:}", escape("$", "${test:}"));
    }

	@Test
    public void testNoEscapeVariableColonNoDollarVariable() {
		assertEquals("${test:}", escape(null, "${test:}"));
    }

	@Test
    public void testNoEscapeColonVariable() {
		assertEquals("${:test}", escape("$", "${:test}"));
    }

	@Test
    public void testNoEscapeColonVariableNoDollarVariable() {
		assertEquals("${:test}", escape(null, "${:test}"));
    }

	@Test
    public void testNoEscapeWithColonVariable() {
		assertEquals("${test:test}", escape("$", "${test:test}"));
    }

	@Test
    public void testNoEscapeWithColonVariableNoDollarVariable() {
		assertEquals("${test:test}", escape(null, "${test:test}"));
    }

	@Test(expected = IllegalArgumentException.class)
	public void testNoDollarEscapeSupported() {
		escape(null, "${test}");
    }

	@Test
    public void testDoubleQuoteBeginning() {
		assertEquals("\"\\\"Test\"", escape(null, "\"Test"));
    }

	@Test
    public void testDoubleQuoteMiddle() {
		assertEquals("\"Test\\\"test\"", escape(null, "Test\"test"));
    }

	@Test
    public void testDoubleQuoteEnd() {
		assertEquals("\"Test\\\"\"", escape(null, "Test\""));
    }

	@Test
    public void testBackslashAlone() {
		assertEquals("\"\\\\\"", escape(null, "\\"));
    }

	@Test
    public void testBackslashBeginning() {
		assertEquals("\\Test", escape(null, "\\Test"));
    }

	@Test
    public void testBackslashMiddle() {
		assertEquals("Test\\test", escape(null, "Test\\test"));
    }

	@Test
    public void testBackslashEnd() {
		// Careful to avoid accidental line contiuation by double-quoting ending with backslash
		assertEquals("\"Test\\\\\"", escape(null, "Test\\"));
    }

	@Test
    public void testDoubleBackslashAlone() {
		assertEquals("\"\\\\\\\\\"", escape(null, "\\\\"));
    }

	@Test
    public void testDoubleBackslashBeginning() {
		assertEquals("\\\\\\Test", escape(null, "\\\\Test"));
    }

	@Test
    public void testDoubleBackslashMiddle() {
		assertEquals("Test\\\\\\test", escape(null, "Test\\\\test"));
    }

	@Test
    public void testDoubleBackslashEnd() {
		// Careful to avoid accidental line contiuation by double-quoting ending with backslash
		assertEquals("\"Test\\\\\\\\\"", escape(null, "Test\\\\"));
    }

	@Test
    public void testBackslashDoubleQuoteAlone() {
		assertEquals("\"\\\\\\\"\"", escape(null, "\\\""));
    }

	@Test
    public void testBackslashDoubleQuoteBeginning() {
		assertEquals("\"\\\\\\\"Test\"", escape(null, "\\\"Test"));
    }

	@Test
    public void testBackslashDoubleQuoteMiddle() {
		assertEquals("\"Test\\\\\\\"test\"", escape(null, "Test\\\"test"));
    }

	@Test
    public void testBackslashDoubleQuoteEnd() {
		assertEquals("\"Test\\\\\\\"\"", escape(null, "Test\\\""));
    }

	@Test
    public void testBackslashSingleQuoteAlone() {
		assertEquals("\"\\'\"", escape(null, "\\'"));
    }

	@Test
    public void testBackslashSingleQuoteBeginning() {
		assertEquals("\"\\'Test\"", escape(null, "\\'Test"));
    }

	@Test
    public void testBackslashSingleQuoteMiddle() {
		assertEquals("\"Test\\'test\"", escape(null, "Test\\'test"));
    }

	@Test
    public void testBackslashSingleQuoteEnd() {
		assertEquals("\"Test\\'\"", escape(null, "Test\\'"));
    }
}
