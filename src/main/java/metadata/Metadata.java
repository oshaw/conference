package metadata;

import org.agrona.MutableDirectBuffer;

public class Metadata extends MetadataDecoder {
    public static final byte TYPE_ALL = -1;
    public static final byte TYPE_AUDIO = 0;
    public static final byte TYPE_VIDEO = 1;

    public void decode(final MutableDirectBuffer buffer, final int offset) {
        final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        final int blockLength;
        final int version;
        
        headerDecoder.wrap(buffer, offset);
        blockLength = headerDecoder.blockLength();
        headerDecoder.templateId();
        headerDecoder.schemaId();
        version = headerDecoder.version();
        super.wrap(buffer, headerDecoder.offset(), blockLength, version);
    }
    
    public static void encode(
        final MutableDirectBuffer buffer,
        final int offset,
        final byte datatype,
        final int address,
        final short port,
        final long time
    ) {
        final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        final MetadataEncoder encoder = new MetadataEncoder();
        
        headerEncoder
            .wrap(buffer, offset)
            .blockLength(encoder.sbeBlockLength())
            .templateId(encoder.sbeTemplateId())
            .schemaId(encoder.sbeSchemaId())
            .version(encoder.sbeSchemaVersion());
        
        encoder
            .wrap(buffer, offset + headerEncoder.offset())
            .datatype(datatype)
            .address(address)
            .port(port)
            .time(time)
            .padding((byte) 0b00000000);
    }
}
