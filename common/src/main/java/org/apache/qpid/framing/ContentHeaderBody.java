/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.framing;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.qpid.QpidException;
import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.codec.MarkableDataInput;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.protocol.AMQVersionAwareProtocolSession;
import org.apache.qpid.transport.ByteBufferSender;

public class ContentHeaderBody implements AMQBody
{
    public static final byte TYPE = 2;
    public static final int CLASS_ID =  60;
    private static final int HEADER_SIZE = 14;

    private long _bodySize;

    /** must never be null */
    private final BasicContentHeaderProperties _properties;

    public ContentHeaderBody(MarkableDataInput buffer, long size) throws AMQFrameDecodingException, IOException
    {
        buffer.readUnsignedShort();
        buffer.readUnsignedShort();
        _bodySize = buffer.readLong();
        int propertyFlags = buffer.readUnsignedShort();
        ContentHeaderPropertiesFactory factory = ContentHeaderPropertiesFactory.getInstance();
        _properties = factory.createContentHeaderProperties(CLASS_ID, propertyFlags, buffer, (int)size - 14);

    }

    public ContentHeaderBody(BasicContentHeaderProperties props)
    {
        _properties = props;
    }

    public ContentHeaderBody(BasicContentHeaderProperties props, long bodySize)
    {
        _properties = props;
        _bodySize = bodySize;
    }

    public byte getFrameType()
    {
        return TYPE;
    }

    /**
     * Helper method that is used currently by the persistence layer.
     * @param buffer buffer to decode
     * @param size size of the body
     *
     * @return the decoded header body
     * @throws AMQFrameDecodingException if there is a decoding issue
     * @throws AMQProtocolVersionException if there is a version issue
     * @throws IOException if there is an IO issue
     */
    public static ContentHeaderBody createFromBuffer(MarkableDataInput buffer, long size)
        throws AMQFrameDecodingException, AMQProtocolVersionException, IOException
    {
        ContentHeaderBody body = new ContentHeaderBody(buffer, size);
        
        return body;
    }

    public int getSize()
    {
        return 2 + 2 + 8 + 2 + _properties.getPropertyListSize();
    }

    public void writePayload(DataOutput buffer) throws IOException
    {
        EncodingUtils.writeUnsignedShort(buffer, CLASS_ID);
        EncodingUtils.writeUnsignedShort(buffer, 0);
        buffer.writeLong(_bodySize);
        EncodingUtils.writeUnsignedShort(buffer, _properties.getPropertyFlags());
        _properties.writePropertyListPayload(buffer);
    }

    @Override
    public long writePayload(final ByteBufferSender sender) throws IOException
    {
        QpidByteBuffer data = QpidByteBuffer.allocateDirect(HEADER_SIZE);
        EncodingUtils.writeUnsignedShort(data, CLASS_ID);
        EncodingUtils.writeUnsignedShort(data, 0);
        data.putLong(_bodySize);
        EncodingUtils.writeUnsignedShort(data, _properties.getPropertyFlags());
        data.flip();
        sender.send(data);
        data.dispose();
        return HEADER_SIZE + _properties.writePropertyListPayload(sender);
    }

    public long writePayload(final QpidByteBuffer buf) throws IOException
    {
        EncodingUtils.writeUnsignedShort(buf, CLASS_ID);
        EncodingUtils.writeUnsignedShort(buf, 0);
        buf.putLong(_bodySize);
        EncodingUtils.writeUnsignedShort(buf, _properties.getPropertyFlags());
        return HEADER_SIZE + _properties.writePropertyListPayload(buf);
    }

    public void handle(final int channelId, final AMQVersionAwareProtocolSession session)
            throws QpidException
    {
        session.contentHeaderReceived(channelId, this);
    }

    public static AMQFrame createAMQFrame(int channelId,
                                          BasicContentHeaderProperties properties,
                                          long bodySize)
    {
        return new AMQFrame(channelId, new ContentHeaderBody(properties, bodySize));
    }

    public BasicContentHeaderProperties getProperties()
    {
        return _properties;
    }

    @Override
    public String toString()
    {
        return "ContentHeaderBody{" +
                "classId=" + CLASS_ID +
                ", weight=" + 0 +
                ", bodySize=" + _bodySize +
                ", properties=" + _properties +
                '}';
    }

    public int getClassId()
    {
        return CLASS_ID;
    }

    public int getWeight()
    {
        return 0;
    }

    /** unsigned long but java can't handle that anyway when allocating byte array
     *
     * @return the body size */
    public long getBodySize()
    {
        return _bodySize;
    }

    public void setBodySize(long bodySize)
    {
        _bodySize = bodySize;
    }

    public static void process(final MarkableDataInput buffer,
                               final ChannelMethodProcessor methodProcessor, final long size)
            throws IOException, AMQFrameDecodingException
    {

        int classId = buffer.readUnsignedShort();
        buffer.readUnsignedShort();
        long bodySize = buffer.readLong();
        int propertyFlags = buffer.readUnsignedShort();

        BasicContentHeaderProperties properties;

        if (classId != CLASS_ID)
        {
            throw new AMQFrameDecodingException(AMQConstant.FRAME_ERROR, "Unsupported content header class id: " + classId, null);
        }
        properties = new BasicContentHeaderProperties();
        properties.populatePropertiesFromBuffer(buffer, propertyFlags, (int)(size-14));

        if(!methodProcessor.ignoreAllButCloseOk())
        {
            methodProcessor.receiveMessageHeader(properties, bodySize);
        }
    }

    public void dispose()
    {
        _properties.dispose();
    }

    public void clearEncodedForm()
    {
        _properties.clearEncodedForm();
    }
}
