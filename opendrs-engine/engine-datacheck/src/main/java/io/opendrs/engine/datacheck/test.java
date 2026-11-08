package io.opendrs.engine.datacheck;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

public class test {

    public static void main(String[] args) {
        // 1. Parameterless types: singletons, and instances are equal by value.
        ArrowType binary = ArrowType.Binary.INSTANCE;
        ArrowType largeBinary = ArrowType.LargeBinary.INSTANCE;
        ArrowType fixedBinary16 = new ArrowType.FixedSizeBinary(16);

        // 2. Parameterized types: plain immutable value objects; parameters are part of the type.
        ArrowType tsUtc = new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC");
        ArrowType tsLocal = new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);

        System.out.println("== Type instances ==");
        dump(binary);
        dump(largeBinary);
        dump(fixedBinary16);
        dump(tsUtc);
        dump(tsLocal);

        System.out.println();
        System.out.println("== Singleton / value semantics ==");
        System.out.println("Binary.INSTANCE == new Binary() ? "
                + (binary == new ArrowType.Binary()));
        System.out.println("tsUtc.equals(same params) ? "
                + tsUtc.equals(new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC")));
        System.out.println("tsUtc.equals(other timezone) ? "
                + tsUtc.equals(new ArrowType.Timestamp(TimeUnit.MILLISECOND, "Asia/Shanghai")));

        System.out.println();
        System.out.println("== ArrowType -> physical vector impl (MinorType) ==");
        System.out.println("Binary             -> " + Types.getMinorTypeForArrowType(binary));
        System.out.println("Timestamp(ms,UTC)  -> " + Types.getMinorTypeForArrowType(tsUtc));
        System.out.println("Timestamp(us,null) -> " + Types.getMinorTypeForArrowType(tsLocal));

        System.out.println();
        System.out.println("== Field / Schema (Field = type + nullability + metadata) ==");
        Field payload = Field.nullable("payload", binary);
        Field createdAt = Field.notNullable("created_at", tsUtc);
        Schema schema = new Schema(List.of(payload, createdAt));
        for (Field field : schema.getFields()) {
            System.out.printf("  %-12s nullable=%-5s type=%s%n",
                    field.getName(),
                    field.getFieldType().isNullable(),
                    field.getFieldType().getType());
        }

        System.out.println();
        System.out.println("== Dispatch by concrete subtype (Java 21 pattern switch) ==");
        for (ArrowType type : List.of(binary, fixedBinary16, tsUtc, tsLocal)) {
            System.out.println("  " + describe(type));
        }

        writeValues(binary, tsUtc, tsLocal);
    }

    /** Create concrete vectors from ArrowType/Field and actually write rows into them. */
    private static void writeValues(ArrowType binary, ArrowType tsUtc, ArrowType tsLocal) {
        System.out.println();
        System.out.println("== Writing real values into vectors ==");

        try (BufferAllocator allocator = new RootAllocator();
             VarBinaryVector payloadVec = (VarBinaryVector)
                     Field.nullable("payload", binary).createVector(allocator);
             TimeStampMilliTZVector createdAtVec = (TimeStampMilliTZVector)
                     Field.nullable("created_at", tsUtc).createVector(allocator);
             TimeStampMicroVector localAtVec = (TimeStampMicroVector)
                     Field.nullable("local_at", tsLocal).createVector(allocator)) {

            // Binary: 2 rows; index 1 is NULL (validity bitmap, no bytes stored).
            payloadVec.allocateNew();
            payloadVec.setSafe(0, "hello-arrow".getBytes(StandardCharsets.UTF_8));
            payloadVec.setNull(1);
            payloadVec.setValueCount(2);

            // Timestamp(MILLISECOND, "UTC"): stored as epoch millis (absolute instant).
            long epochMillis = Instant.parse("2026-11-07T08:30:00Z").toEpochMilli();
            createdAtVec.allocateNew();
            createdAtVec.setSafe(0, epochMillis);
            createdAtVec.setNull(1);
            createdAtVec.setValueCount(2);

            // Timestamp(MICROSECOND, null): stored as epoch micros; no zone in the type itself.
            long epochMicros = epochMillis * 1_000L + 123L;
            localAtVec.allocateNew();
            localAtVec.setSafe(0, epochMicros);
            localAtVec.setNull(1);
            localAtVec.setValueCount(2);

            System.out.println("  row | payload(hex, utf8)        | created_at (UTC)                  | local_at");
            for (int i = 0; i < payloadVec.getValueCount(); i++) {
                byte[] bytes = payloadVec.getObject(i);
                Long createdMs = createdAtVec.getObject(i); // TZ vector returns raw epoch millis

                String payloadDesc = bytes == null
                        ? "null"
                        : HexFormat.of().formatHex(bytes)
                        + " \"" + new String(bytes, StandardCharsets.UTF_8) + "\"";
                String createdDesc = createdMs == null
                        ? "null"
                        : Instant.ofEpochMilli(createdMs) + " (" + createdMs + ")";
                String localDesc;
                Object local = localAtVec.getObject(i); // no-TZ vector returns LocalDateTime
                if (local == null) {
                    localDesc = "null";
                } else {
                    localDesc = local + " (raw=" + localAtVec.get(i) + "us)";
                }

                System.out.printf("  %3d | %-25s | %-33s | %s%n",
                        i, payloadDesc, createdDesc, localDesc);
            }

            System.out.println("  payload.valueCount=" + payloadVec.getValueCount()
                    + ", createdAt.valueCount=" + createdAtVec.getValueCount()
                    + ", localAt.valueCount=" + localAtVec.getValueCount());
            System.out.println("  createdAt.get(0) raw = " + createdAtVec.get(0) + " (epoch millis)");
            System.out.println("  localAt.get(0)   raw = " + localAtVec.get(0) + " (epoch micros)");
        }
    }

    /** Print three layers of an ArrowType: POJO string, ArrowTypeID, FlatBuffers enum id. */
    private static void dump(ArrowType type) {
        System.out.printf("%-34s typeID=%-14s complex=%-5s flatbufType=%d  class=%s%n",
                type,
                type.getTypeID(),
                type.isComplex(),
                type.getTypeID().getFlatbufID(),
                type.getClass().getSimpleName());
    }

    /** Pull out the parameters actually carried by a subtype. */
    private static String describe(ArrowType type) {
        return switch (type) {
            case ArrowType.Binary b -> "Binary: variable-length bytes, no params";
            case ArrowType.FixedSizeBinary b -> "FixedSizeBinary: " + b.getByteWidth() + " bytes per row";
            case ArrowType.Timestamp t -> "Timestamp: unit=" + t.getUnit()
                    + ", timezone=" + t.getTimezone()
                    + (t.getTimezone() == null ? " (no zone, interpreted as local time)" : " (zoned, absolute instant)");
            default -> type.toString();
        };
    }
}
