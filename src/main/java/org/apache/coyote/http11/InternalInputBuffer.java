/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.HeaderUtil;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * InputBuffer的实现提供HTTP请求头解析以及传输解码。
 * <p></p>
 * Implementation of InputBuffer which provides HTTP request header parsing as
 * well as transfer decoding.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalInputBuffer extends AbstractInputBuffer<Socket> {

    private static final Log log = LogFactory.getLog(InternalInputBuffer.class);


    /**
     * Underlying input stream.
     */
    private InputStream inputStream;


    /**
     * Default constructor.
     */
    public InternalInputBuffer(Request request, int headerBufferSize,
            boolean rejectIllegalHeader, HttpParser httpParser) {

        this.request = request;
        headers = request.getMimeHeaders();

        buf = new byte[headerBufferSize];

        this.rejectIllegalHeaderName = rejectIllegalHeader;
        this.httpParser = httpParser;

        inputStreamInputBuffer = new InputStreamInputBuffer();

        filterLibrary = new InputFilter[0];
        activeFilters = new InputFilter[0];
        lastActiveFilter = -1;

        parsingHeader = true;
        swallowInput = true;

    }


    /**
     * 读取请求行。此函数用于在HTTP请求头解析期间使用。不要试图使用它来读取请求体.<br>
     * 内容看似复杂，其实就是为了解析请求行的各个部分并保存起来，同时进行一些参数的检查工作.<br>
     * 请求行如：POST /github/collect HTTP/1.1
     * <p></p>
     * Read the request line. This function is meant to be used during the
     * HTTP request header parsing. Do NOT attempt to read the request body
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accommodate
     * the whole line.
     */
    @Override
    public boolean parseRequestLine(boolean useAvailableDataOnly)

        throws IOException {

        int start = 0;

        //
        // Skipping blank lines. 跳过空行
        //

        do {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            // Set the start time once we start reading data (even if it is
            // just skipping blank lines)
            if (request.getStartTime() < 0) {
                request.setStartTime(System.currentTimeMillis());
            }
            chr = buf[pos++];
        } while (chr == Constants.CR || chr == Constants.LF); // 跳过回车和换行

        // 因为pos++，所以每次循环只处理了pos而还没有处理pos+1的位置，所以后续处理需要将pos减1进行恢复
        pos--;

        // Mark the current buffer position. HTTP方法名的起始位置
        start = pos;

        //
        // Reading the method name. 读取HTTP方法名
        // Method name is a token
        //

        boolean space = false;

        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            // Spec says method name is a token followed by a single SP but
            // also be tolerant of multiple SP and/or HT.
            // 如果遇到空格和制表符，表示到达方法名的末尾
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                space = true;
                // 保证请求行的方法名
                request.method().setBytes(buf, start, pos - start);
            } else if (!HttpParser.isToken(buf[pos])) {
                String invalidMethodValue = parseInvalid(start, buf);
                throw new IllegalArgumentException(sm.getString("iib.invalidmethod", invalidMethodValue));
            }

            pos++;

        }

        // 跳过空格和制表符
        // Spec says single SP but also be tolerant of multiple SP and/or HT
        while (space) {
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            // 跳过空格和制表符
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                pos++;
            } else {
                space = false;
            }
        }

        // Mark the current buffer position
        start = pos;
        int end = 0;
        int questionPos = -1;

        //
        // Reading the URI. 读取HTTP请求行中的请求路径
        //
        // 是否到了行的末尾？
        boolean eol = false;

        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            // 遇到回车换行，但不符合HTTP/0.9的请求格式
            if (buf[pos -1] == Constants.CR && buf[pos] != Constants.LF) {
                // CR not followed by LF so not an HTTP/0.9 request and
                // therefore invalid. Trigger error handling.
                // Avoid unknown protocol triggering an additional error
                request.protocol().setString(Constants.HTTP_11);
                String invalidRequestTarget = parseInvalid(start, buf);
                throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
            }

            // 遇到空格或制表符，表示到了URI的末尾
            // Spec says single SP but it also says be tolerant of HT
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                space = true;
                end = pos;
            } else if (buf[pos] == Constants.CR) { // 回车. HTTP/0.9风格的请求. CR是可选的
                // HTTP/0.9 style request. CR is optional. LF is not.
            } else if (buf[pos] == Constants.LF) { // 换行. HTTP/0.9风格的请求. 停止处理
                // HTTP/0.9 style request
                // Stop this processing loop
                space = true;
                // Set blank protocol (indicates HTTP/0.9)
                request.protocol().setString("");
                // Skip the protocol processing
                eol = true;
                if (buf[pos - 1] == Constants.CR) { // 如果LF之前是CR，则end为pos减1
                    end = pos - 1;
                } else {
                    end = pos; // 如果LF之前没有CR，则end为pos
                }
            } else if ((buf[pos] == Constants.QUESTION) && (questionPos == -1)) { // 问号
                questionPos = pos;
            } else if (questionPos != -1 && !httpParser.isQueryRelaxed(buf[pos])) { // 查询参数检查
                // %nn decoding will be checked at the point of decoding
                String invalidRequestTarget = parseInvalid(start, buf);
                throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
            } else if (httpParser.isNotRequestTargetRelaxed(buf[pos])) {
                // This is a general check that aims to catch problems early
                // Detailed checking of each part of the request target will
                // happen in AbstractHttp11Processor#prepareRequest()
                String invalidRequestTarget = parseInvalid(start, buf);
                throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget", invalidRequestTarget));
            }
            // 继续检查下一个位置
            pos++;
        }

        // 保存请求行的请求URI，包含?及之后的查询参数
        request.unparsedURI().setBytes(buf, start, end - start);

        // 保存请求行的请求URI及请求参数
        if (questionPos >= 0) {
            // 保存查询参数
            request.queryString().setBytes(buf, questionPos + 1,
                                           end - questionPos - 1);
            // 不含?及之后的查询参数
            request.requestURI().setBytes(buf, start, questionPos - start);
        } else { // 没请求参数，直接保存URI
            request.requestURI().setBytes(buf, start, end - start);
        }

        // 忽略空格和制表符
        // Spec says single SP but also says be tolerant of multiple SP and/or HT
        while (space && !eol) {
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                pos++;
            } else {
                space = false;
            }
        }

        // Mark the current buffer position
        start = pos;
        end = 0;

        //
        // Reading the protocol. 读取请求行的协议信息
        // Protocol is always "HTTP/" DIGIT "." DIGIT
        //
        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.CR) {
                // Possible end of request line. Need LF next.
            } else if (buf[pos - 1] == Constants.CR && buf[pos] == Constants.LF) { // 当前行结束
                end = pos - 1;
                eol = true;
            } else if (!HttpParser.isHttpProtocol(buf[pos])) {
                String invalidProtocol = parseInvalid(start, buf);
                throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol", invalidProtocol));
            }

            pos++;

        }

        // 保存请求行的协议信息
        if ((end - start) > 0) {
            request.protocol().setBytes(buf, start, end - start);
        }

        if (request.protocol().isNull()) {
            throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol"));
        }

        return true;
    }


    /**
     * 解析HTTP请求头。
     * <p></p>
     * Parse the HTTP headers.
     */
    @Override
    public boolean parseHeaders()
        throws IOException {
        if (!parsingHeader) {
            throw new IllegalStateException(
                    sm.getString("iib.parseheaders.ise.error"));
        }

        // 循环解析出请求头的每一个字段
        while (parseHeader()) {
            // Loop until we run out of headers
        }

        parsingHeader = false;
        end = pos;
        return true;
    }


    /**
     * 解析一行HTTP请求头。
     * <p></p>
     * Parse an HTTP header.
     *
     * @return false after reading a blank line (which indicates that the
     * HTTP header parsing is done
     */
    @SuppressWarnings("null") // headerValue cannot be null
    private boolean parseHeader() throws IOException {
        // 忽略掉无效的回车（CR）？
        while (true) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            prevChr = chr;
            chr = buf[pos];

            if (chr == Constants.CR && prevChr != Constants.CR) { // 非回车+回车
                // Possible start of CRLF - process the next byte. 可能是CRLF，处理下一个字节看看
            } else if (prevChr == Constants.CR && chr == Constants.LF) { // 回车+换行，表示遇到请求头结束标志
                pos++;
                return false;
            } else { // 非CRLF
                if (prevChr == Constants.CR) { // 回车+非换行
                    // Must have read two bytes (first was CR, second was not LF)
                    pos--;
                }
                break;
            }

            pos++;
        }

        // Mark the current buffer position
        // 当前处理逻辑的起始位置
        int start = pos;
        // 当前行的起始位置
        int lineStart = start;

        //
        // Reading the header name
        // Header name is always US-ASCII
        //

        // 是否遇到了冒号:
        boolean colon = false;
        // 请求头字段的值对象
        MessageBytes headerValue = null;

        // 解析请求头字段名
        while (!colon) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.COLON) {
                colon = true;
                // 创建请求头对象，保存请求头字段的名称，最后返回请求头字段的值对象
                headerValue = headers.addValue(buf, start, pos - start);
            } else if (!HttpParser.isToken(buf[pos])) {
                // Non-token characters are illegal in header names
                // Parsing continues so the error can be reported in context
                // skipLine() will handle the error
                skipLine(lineStart, start);
                return true;
            }

            chr = buf[pos];
            if ((chr >= Constants.A) && (chr <= Constants.Z)) {
                buf[pos] = (byte) (chr - Constants.LC_OFFSET);
            }

            pos++;

        }

        // Mark the current buffer position
        start = pos;
        int realPos = pos;

        //
        // Reading the header value (which can be spanned over multiple lines)
        //

        // 是否读到了行末
        boolean eol = false;
        boolean validLine = true;

        while (validLine) {

            boolean space = true;

            // Skipping spaces. 忽略空格
            while (space) {

                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill())
                        throw new EOFException(sm.getString("iib.eof.error"));
                }

                if ((buf[pos] == Constants.SP) || (buf[pos] == Constants.HT)) {
                    pos++;
                } else {
                    space = false;
                }

            }

            int lastSignificantChar = realPos;

            // 从当前位置读到行末
            // Reading bytes until the end of the line
            while (!eol) {

                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill())
                        throw new EOFException(sm.getString("iib.eof.error"));
                }

                prevChr = chr;
                chr = buf[pos];
                if (chr == Constants.CR) {
                    // Possible start of CRLF - process the next byte.
                } else if (prevChr == Constants.CR && chr == Constants.LF) {
                    eol = true;
                } else if (prevChr == Constants.CR) { // 无效的请求头值，删除该请求头字段
                    // Invalid value
                    // Delete the header (it will be the most recent one)
                    headers.removeHeader(headers.size() - 1);
                    skipLine(lineStart, start);
                    return true;
                } else if (chr != Constants.HT && HttpParser.isControl(chr)) { // 无效的请求头值，删除该请求头字段
                    // Invalid value
                    // Delete the header (it will be the most recent one)
                    headers.removeHeader(headers.size() - 1);
                    skipLine(lineStart, start);
                    return true;
                } else if (chr == Constants.SP) {
                    buf[realPos] = chr;
                    realPos++;
                } else {
                    buf[realPos] = chr;
                    realPos++;
                    lastSignificantChar = realPos;
                }

                pos++;

            }

            realPos = lastSignificantChar;

            // Checking the first character of the new line. If the character
            // is a LWS, then it's a multiline header

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            byte peek = buf[pos];
            if (peek != Constants.SP && peek != Constants.HT) {
                validLine = false;
            } else {
                eol = false;
                // Copying one extra space in the buffer (since there must
                // be at least one space inserted between the lines)
                buf[realPos] = peek;
                realPos++;
            }

        }

        // Set the header value. 保存请求头字段的值
        headerValue.setBytes(buf, start, realPos - start);

        return true;

    }


    @Override
    public void recycle() {
        super.recycle();
        inputStream = null;
    }


    // ------------------------------------------------------ Protected Methods


    @Override
    protected void init(SocketWrapper<Socket> socketWrapper,
            AbstractEndpoint<Socket> endpoint) throws IOException {
        inputStream = socketWrapper.getSocket().getInputStream();
    }



    private void skipLine(int lineStart, int start) throws IOException {
        boolean eol = false;
        int lastRealByte = start;
        if (pos - 1 > start) {
            lastRealByte = pos - 1;
        }

        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            prevChr = chr;
            chr = buf[pos];

            if (chr == Constants.CR) {
                // Skip
            } else if (prevChr == Constants.CR && chr == Constants.LF) {
                eol = true;
            } else {
                lastRealByte = pos;
            }
            pos++;
        }

        if (rejectIllegalHeaderName || log.isDebugEnabled()) {
            String message = sm.getString("iib.invalidheader", HeaderUtil.toPrintableString(
                    buf, lineStart, lastRealByte - lineStart + 1));
            if (rejectIllegalHeaderName) {
                throw new IllegalArgumentException(message);
            }
            log.debug(message);
        }
    }

    /**
     * Fill the internal buffer using data from the underlying input stream.
     *
     * @return false if at end of stream
     */
    protected boolean fill() throws IOException {
        return fill(true);
    }

    @Override
    protected boolean fill(boolean block) throws IOException {

        int nRead = 0;

        if (parsingHeader) {

            if (lastValid == buf.length) {
                throw new IllegalArgumentException
                    (sm.getString("iib.requestheadertoolarge.error"));
            }
            // 从输入流的pos位置开始，读取最多buf.length - lastValid个字节到buf缓冲区
            nRead = inputStream.read(buf, pos, buf.length - lastValid);
            if (nRead > 0) {
                lastValid = pos + nRead;
            }

        } else {
            // 由于请求头比较大，剩下的缓冲区比较小，因此重置缓冲区
            if (buf.length - end < 4500) {
                // In this case, the request header was really large, so we allocate a
                // brand new one; the old one will get GCed when subsequent requests
                // clear all references
                buf = new byte[buf.length];
                end = 0;
            }
            pos = end;
            lastValid = pos;
            nRead = inputStream.read(buf, pos, buf.length - lastValid);
            if (nRead > 0) {
                lastValid = pos + nRead;
            }

        }

        return (nRead > 0);

    }


    // ------------------------------------- InputStreamInputBuffer Inner Class

    /**
     * This class is an input buffer which will read its data from an input
     * stream.
     */
    protected class InputStreamInputBuffer implements InputBuffer {

        /**
         * Read bytes into the specified chunk.
         */
        @Override
        public int doRead(ByteChunk chunk, Request req )
            throws IOException {

            if (pos >= lastValid) {
                if (!fill())
                    return -1;
            }

            int length = lastValid - pos;
            chunk.setBytes(buf, pos, length);
            pos = lastValid;

            return (length);
        }

        @Override
        public int available() {
            if (lastValid > pos) {
                return lastValid - pos;
            } else {
                return 0;
            }
        }
    }
}
