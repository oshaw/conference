package metadata;

import org.agrona.MutableDirectBuffer;

public class Metadata {
    public Metadata(final MutableDirectBuffer buffer, final int offset) {
        final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        final MetadataDecoder decoder = new MetadataDecoder();
        final int blockLength;
        final int version;

        headerDecoder.wrap(buffer, offset);
        blockLength = headerDecoder.blockLength();
        headerDecoder.templateId();
        headerDecoder.schemaId();
        version = headerDecoder.version();
        decoder.wrap(buffer, headerDecoder.offset(), blockLength, version);
    }
    
    public static void encode(
        final MutableDirectBuffer buffer,
        final int offset,
        final byte type,
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
            .flags((byte) 0b10101010)
            .address(address)
            .port(port)
            .time(time)
            .padding((byte) 0b00000000);
    }
}
