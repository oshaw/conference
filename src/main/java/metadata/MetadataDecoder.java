/* Generated SBE (Simple Binary Encoding) message codec. */
package metadata;

import org.agrona.DirectBuffer;

@SuppressWarnings("all")
class MetadataDecoder
{
    public static final int BLOCK_LENGTH = 16;
    public static final int TEMPLATE_ID = 1;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final MetadataDecoder parentMessage = this;
    private DirectBuffer buffer;
    protected int initialOffset;
    protected int offset;
    protected int limit;
    protected int actingBlockLength;
    protected int actingVersion;

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

    public DirectBuffer buffer()
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

    public MetadataDecoder wrap(
        final DirectBuffer buffer,
        final int offset,
        final int actingBlockLength,
        final int actingVersion)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.initialOffset = offset;
        this.offset = offset;
        this.actingBlockLength = actingBlockLength;
        this.actingVersion = actingVersion;
        limit(offset + actingBlockLength);

        return this;
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

    public byte flags()
    {
        return buffer.getByte(offset + 0);
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

    public long address()
    {
        return (buffer.getInt(offset + 1, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF_FFFFL);
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

    public int port()
    {
        return (buffer.getShort(offset + 5, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF);
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

    public long time()
    {
        return buffer.getLong(offset + 7, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public byte padding()
    {
        return buffer.getByte(offset + 15);
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final MetadataDecoder decoder = new MetadataDecoder();
        decoder.wrap(buffer, initialOffset, actingBlockLength, actingVersion);

        return decoder.appendTo(new StringBuilder()).toString();
    }

    public StringBuilder appendTo(final StringBuilder builder)
    {
        if (null == buffer)
        {
            return builder;
        }

        final int originalLimit = limit();
        limit(initialOffset + actingBlockLength);
        builder.append("[Metadata](sbeTemplateId=");
        builder.append(TEMPLATE_ID);
        builder.append("|sbeSchemaId=");
        builder.append(SCHEMA_ID);
        builder.append("|sbeSchemaVersion=");
        if (parentMessage.actingVersion != SCHEMA_VERSION)
        {
            builder.append(parentMessage.actingVersion);
            builder.append('/');
        }
        builder.append(SCHEMA_VERSION);
        builder.append("|sbeBlockLength=");
        if (actingBlockLength != BLOCK_LENGTH)
        {
            builder.append(actingBlockLength);
            builder.append('/');
        }
        builder.append(BLOCK_LENGTH);
        builder.append("):");
        builder.append("flags=");
        builder.append(flags());
        builder.append('|');
        builder.append("address=");
        builder.append(address());
        builder.append('|');
        builder.append("port=");
        builder.append(port());
        builder.append('|');
        builder.append("time=");
        builder.append(time());
        builder.append('|');
        builder.append("padding=");
        builder.append(padding());

        limit(originalLimit);

        return builder;
    }
}
