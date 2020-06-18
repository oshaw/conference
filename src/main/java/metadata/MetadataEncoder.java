/* Generated SBE (Simple Binary Encoding) message codec. */
package metadata;

import org.agrona.MutableDirectBuffer;

@SuppressWarnings("all")
class MetadataEncoder
{
    public static final int BLOCK_LENGTH = 16;
    public static final int TEMPLATE_ID = 1;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final MetadataEncoder parentMessage = this;
    private MutableDirectBuffer buffer;
    protected int initialOffset;
    protected int offset;
    protected int limit;

    public int sbeBlockLength()
    {
        return BLOCK_LENGTH;
    }

    public int sbeTemplateId()
    {
        return TEMPLATE_ID;
    }

    public int sbeSchemaId()
    {
        return SCHEMA_ID;
    }

    public int sbeSchemaVersion()
    {
        return SCHEMA_VERSION;
    }

    public String sbeSemanticType()
    {
        return "";
    }

    public MutableDirectBuffer buffer()
    {
        return buffer;
    }

    public int initialOffset()
    {
        return initialOffset;
    }

    public int offset()
    {
        return offset;
    }

    public MetadataEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.initialOffset = offset;
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public MetadataEncoder wrapAndApplyHeader(
        final MutableDirectBuffer buffer, final int offset, final MessageHeaderEncoder headerEncoder)
    {
        headerEncoder
            .wrap(buffer, offset)
            .blockLength(BLOCK_LENGTH)
            .templateId(TEMPLATE_ID)
            .schemaId(SCHEMA_ID)
            .version(SCHEMA_VERSION);

        return wrap(buffer, offset + MessageHeaderEncoder.ENCODED_LENGTH);
    }

    public int encodedLength()
    {
        return limit - offset;
    }

    public int limit()
    {
        return limit;
    }

    public void limit(final int limit)
    {
        this.limit = limit;
    }

    public static int flagsId()
    {
        return 1;
    }

    public static int flagsSinceVersion()
    {
        return 0;
    }

    public static int flagsEncodingOffset()
    {
        return 0;
    }

    public static int flagsEncodingLength()
    {
        return 1;
    }

    public static String flagsMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte flagsNullValue()
    {
        return (byte)-128;
    }

    public static byte flagsMinValue()
    {
        return (byte)-127;
    }

    public static byte flagsMaxValue()
    {
        return (byte)127;
    }

    public MetadataEncoder flags(final byte value)
    {
        buffer.putByte(offset + 0, value);
        return this;
    }


    public static int addressId()
    {
        return 2;
    }

    public static int addressSinceVersion()
    {
        return 0;
    }

    public static int addressEncodingOffset()
    {
        return 1;
    }

    public static int addressEncodingLength()
    {
        return 4;
    }

    public static String addressMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long addressNullValue()
    {
        return 4294967295L;
    }

    public static long addressMinValue()
    {
        return 0L;
    }

    public static long addressMaxValue()
    {
        return 4294967294L;
    }

    public MetadataEncoder address(final long value)
    {
        buffer.putInt(offset + 1, (int)value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int portId()
    {
        return 3;
    }

    public static int portSinceVersion()
    {
        return 0;
    }

    public static int portEncodingOffset()
    {
        return 5;
    }

    public static int portEncodingLength()
    {
        return 2;
    }

    public static String portMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static int portNullValue()
    {
        return 65535;
    }

    public static int portMinValue()
    {
        return 0;
    }

    public static int portMaxValue()
    {
        return 65534;
    }

    public MetadataEncoder port(final int value)
    {
        buffer.putShort(offset + 5, (short)value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int timeId()
    {
        return 4;
    }

    public static int timeSinceVersion()
    {
        return 0;
    }

    public static int timeEncodingOffset()
    {
        return 7;
    }

    public static int timeEncodingLength()
    {
        return 8;
    }

    public static String timeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long timeNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long timeMinValue()
    {
        return 0x0L;
    }

    public static long timeMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public MetadataEncoder time(final long value)
    {
        buffer.putLong(offset + 7, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int paddingId()
    {
        return 5;
    }

    public static int paddingSinceVersion()
    {
        return 0;
    }

    public static int paddingEncodingOffset()
    {
        return 15;
    }

    public static int paddingEncodingLength()
    {
        return 1;
    }

    public static String paddingMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte paddingNullValue()
    {
        return (byte)-128;
    }

    public static byte paddingMinValue()
    {
        return (byte)-127;
    }

    public static byte paddingMaxValue()
    {
        return (byte)127;
    }

    public MetadataEncoder padding(final byte value)
    {
        buffer.putByte(offset + 15, value);
        return this;
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        return appendTo(new StringBuilder()).toString();
    }

    public StringBuilder appendTo(final StringBuilder builder)
    {
        if (null == buffer)
        {
            return builder;
        }

        final MetadataDecoder decoder = new MetadataDecoder();
        decoder.wrap(buffer, initialOffset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
