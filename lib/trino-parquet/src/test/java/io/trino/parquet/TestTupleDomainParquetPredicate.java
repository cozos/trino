/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.parquet;

import com.google.common.collect.ImmutableMap;
import com.google.common.math.LongMath;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.parquet.predicate.DictionaryDescriptor;
import io.trino.parquet.predicate.TupleDomainParquetPredicate;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.LongTimestamp;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import org.apache.parquet.bytes.LittleEndianDataOutputStream;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.statistics.BinaryStatistics;
import org.apache.parquet.column.statistics.BooleanStatistics;
import org.apache.parquet.column.statistics.DoubleStatistics;
import org.apache.parquet.column.statistics.FloatStatistics;
import org.apache.parquet.column.statistics.IntStatistics;
import org.apache.parquet.column.statistics.LongStatistics;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Types;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.parquet.ParquetEncoding.PLAIN_DICTIONARY;
import static io.trino.parquet.ParquetTimestampUtils.JULIAN_EPOCH_OFFSET_DAYS;
import static io.trino.parquet.predicate.TupleDomainParquetPredicate.getDomain;
import static io.trino.spi.predicate.Domain.all;
import static io.trino.spi.predicate.Domain.create;
import static io.trino.spi.predicate.Domain.notNull;
import static io.trino.spi.predicate.Domain.singleValue;
import static io.trino.spi.predicate.Range.range;
import static io.trino.spi.predicate.TupleDomain.withColumnDomains;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.MAX_SHORT_PRECISION;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.trino.spi.type.Timestamps.NANOSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_NANOSECOND;
import static io.trino.spi.type.Timestamps.round;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimal;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static java.lang.Float.NaN;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.temporal.ChronoField.MICRO_OF_SECOND;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.parquet.column.statistics.Statistics.getStatsBasedOnType;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FLOAT;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT96;
import static org.apache.parquet.schema.Type.Repetition.OPTIONAL;
import static org.apache.parquet.schema.Type.Repetition.REQUIRED;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.joda.time.DateTimeZone.UTC;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestTupleDomainParquetPredicate
{
    private static final ParquetDataSourceId ID = new ParquetDataSourceId("testFile");

    @Test
    public void testBoolean()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(PrimitiveTypeName.BOOLEAN, "BooleanColumn");
        assertEquals(getDomain(columnDescriptor, BOOLEAN, 0L, null, ID, UTC), all(BOOLEAN));

        assertEquals(getDomain(columnDescriptor, BOOLEAN, 10, booleanColumnStats(true, true), ID, UTC), singleValue(BOOLEAN, true));
        assertEquals(getDomain(columnDescriptor, BOOLEAN, 10, booleanColumnStats(false, false), ID, UTC), singleValue(BOOLEAN, false));

        assertEquals(getDomain(columnDescriptor, BOOLEAN, 20, booleanColumnStats(false, true), ID, UTC), all(BOOLEAN));
    }

    private static BooleanStatistics booleanColumnStats(boolean minimum, boolean maximum)
    {
        BooleanStatistics statistics = new BooleanStatistics();
        statistics.setMinMax(minimum, maximum);
        return statistics;
    }

    @Test
    public void testBigint()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT64, "BigintColumn");
        assertEquals(getDomain(columnDescriptor, BIGINT, 0, null, ID, UTC), all(BIGINT));

        assertEquals(getDomain(columnDescriptor, BIGINT, 10, longColumnStats(100L, 100L), ID, UTC), singleValue(BIGINT, 100L));

        assertEquals(getDomain(columnDescriptor, BIGINT, 10, longColumnStats(0L, 100L), ID, UTC), create(ValueSet.ofRanges(range(BIGINT, 0L, true, 100L, true)), false));

        assertEquals(getDomain(columnDescriptor, BIGINT, 20, longOnlyNullsStats(10), ID, UTC), create(ValueSet.all(BIGINT), true));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, BIGINT, 10, longColumnStats(100L, 10L), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int64 BigintColumn\" in Parquet file \"testFile\": [min: 100, max: 10, num_nulls: 0]");
    }

    @Test
    public void testInteger()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT32, "IntegerColumn");
        assertEquals(getDomain(columnDescriptor, INTEGER, 0, null, ID, UTC), all(INTEGER));

        assertEquals(getDomain(columnDescriptor, INTEGER, 10, longColumnStats(100, 100), ID, UTC), singleValue(INTEGER, 100L));

        assertEquals(getDomain(columnDescriptor, INTEGER, 10, longColumnStats(0, 100), ID, UTC), create(ValueSet.ofRanges(range(INTEGER, 0L, true, 100L, true)), false));

        assertEquals(getDomain(columnDescriptor, INTEGER, 20, longColumnStats(0, 2147483648L), ID, UTC), notNull(INTEGER));

        assertEquals(getDomain(columnDescriptor, INTEGER, 20, longOnlyNullsStats(10), ID, UTC), create(ValueSet.all(INTEGER), true));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, INTEGER, 10, longColumnStats(2147483648L, 10), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int32 IntegerColumn\" in Parquet file \"testFile\": [min: 2147483648, max: 10, num_nulls: 0]");
    }

    @Test
    public void testSmallint()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT32, "SmallintColumn");
        assertEquals(getDomain(columnDescriptor, SMALLINT, 0, null, ID, UTC), all(SMALLINT));

        assertEquals(getDomain(columnDescriptor, SMALLINT, 10, longColumnStats(100, 100), ID, UTC), singleValue(SMALLINT, 100L));

        assertEquals(getDomain(columnDescriptor, SMALLINT, 10, longColumnStats(0, 100), ID, UTC), create(ValueSet.ofRanges(range(SMALLINT, 0L, true, 100L, true)), false));

        assertEquals(getDomain(columnDescriptor, SMALLINT, 20, longColumnStats(0, 2147483648L), ID, UTC), notNull(SMALLINT));

        assertEquals(getDomain(columnDescriptor, SMALLINT, 20, longOnlyNullsStats(10), ID, UTC), create(ValueSet.all(SMALLINT), true));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, SMALLINT, 10, longColumnStats(2147483648L, 10), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int32 SmallintColumn\" in Parquet file \"testFile\": [min: 2147483648, max: 10, num_nulls: 0]");
    }

    @Test
    public void testTinyint()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT32, "TinyintColumn");
        assertEquals(getDomain(columnDescriptor, TINYINT, 0, null, ID, UTC), all(TINYINT));

        assertEquals(getDomain(columnDescriptor, TINYINT, 10, longColumnStats(100, 100), ID, UTC), singleValue(TINYINT, 100L));

        assertEquals(getDomain(columnDescriptor, TINYINT, 10, longColumnStats(0, 100), ID, UTC), create(ValueSet.ofRanges(range(TINYINT, 0L, true, 100L, true)), false));

        assertEquals(getDomain(columnDescriptor, TINYINT, 20, longColumnStats(0, 2147483648L), ID, UTC), notNull(TINYINT));

        assertEquals(getDomain(columnDescriptor, TINYINT, 20, longOnlyNullsStats(10), ID, UTC), create(ValueSet.all(TINYINT), true));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, TINYINT, 10, longColumnStats(2147483648L, 10), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int32 TinyintColumn\" in Parquet file \"testFile\": [min: 2147483648, max: 10, num_nulls: 0]");
    }

    @Test
    public void testShortDecimal()
            throws Exception
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT32, "ShortDecimalColumn");
        Type type = createDecimalType(5, 2);
        assertEquals(getDomain(columnDescriptor, type, 0, null, ID, UTC), all(type));

        assertEquals(getDomain(columnDescriptor, type, 10, longColumnStats(10012L, 10012L), ID, UTC), singleValue(type, 10012L));
        // Test that statistics overflowing the size of the type are not used
        assertEquals(getDomain(columnDescriptor, type, 10, longColumnStats(100012L, 100012L), ID, UTC), notNull(type));

        assertEquals(getDomain(columnDescriptor, type, 10, longColumnStats(0L, 100L), ID, UTC), create(ValueSet.ofRanges(range(type, 0L, true, 100L, true)), false));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, type, 10, longColumnStats(100L, 10L), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int32 ShortDecimalColumn\" in Parquet file \"testFile\": [min: 100, max: 10, num_nulls: 0]");
    }

    @Test
    public void testShortDecimalWithNoScale()
            throws Exception
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT32, "ShortDecimalColumnWithNoScale");
        Type type = createDecimalType(5, 0);
        assertEquals(getDomain(columnDescriptor, type, 0, null, ID, UTC), all(type));

        assertEquals(getDomain(columnDescriptor, type, 10, longColumnStats(100L, 100L), ID, UTC), singleValue(type, 100L));
        // Test that statistics overflowing the size of the type are not used
        assertEquals(getDomain(columnDescriptor, type, 10, longColumnStats(123456L, 123456), ID, UTC), notNull(type));

        assertEquals(getDomain(columnDescriptor, type, 10, longColumnStats(0L, 100L), ID, UTC), create(ValueSet.ofRanges(range(type, 0L, true, 100L, true)), false));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, type, 10, longColumnStats(100L, 10L), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int32 ShortDecimalColumnWithNoScale\" in Parquet file \"testFile\": [min: 100, max: 10, num_nulls: 0]");
    }

    @Test
    public void testLongDecimal()
            throws Exception
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(FIXED_LEN_BYTE_ARRAY, "LongDecimalColumn");
        DecimalType type = createDecimalType(20, 5);
        BigInteger maximum = new BigInteger("12345678901234512345");

        Slice zero = unscaledDecimal(BigInteger.valueOf(0L));
        Slice hundred = unscaledDecimal(BigInteger.valueOf(100L));
        Slice max = unscaledDecimal(maximum);

        assertEquals(getDomain(columnDescriptor, type, 0, null, ID, UTC), all(type));
        assertEquals(getDomain(columnDescriptor, type, 10, binaryColumnStats(maximum, maximum), ID, UTC), singleValue(type, max));

        assertEquals(
                getDomain(columnDescriptor, type, 10, binaryColumnStats(0L, 100L), ID, UTC),
                create(ValueSet.ofRanges(range(type, zero, true, hundred, true)), false));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, type, 10, binaryColumnStats(100L, 10L), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required fixed_len_byte_array(0) LongDecimalColumn\" in Parquet file \"testFile\": [min: 0x64, max: 0x0A, num_nulls: 0]");
    }

    @Test
    public void testLongDecimalWithNoScale()
            throws Exception
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(FIXED_LEN_BYTE_ARRAY, "LongDecimalColumnWithNoScale");
        DecimalType type = createDecimalType(20, 0);
        Slice zero = unscaledDecimal(BigInteger.valueOf(0L));
        Slice hundred = unscaledDecimal(BigInteger.valueOf(100L));
        assertEquals(getDomain(columnDescriptor, type, 0, null, ID, UTC), all(type));

        assertEquals(getDomain(columnDescriptor, type, 10, binaryColumnStats(100L, 100L), ID, UTC), singleValue(type, hundred));

        assertEquals(getDomain(columnDescriptor, type, 10, binaryColumnStats(0L, 100L), ID, UTC), create(ValueSet.ofRanges(range(type, zero, true, hundred, true)), false));
        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, type, 10, binaryColumnStats(100L, 10L), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required fixed_len_byte_array(0) LongDecimalColumnWithNoScale\" in Parquet file \"testFile\": [min: 0x64, max: 0x0A, num_nulls: 0]");
    }

    @Test
    public void testDouble()
            throws Exception
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(PrimitiveTypeName.DOUBLE, "DoubleColumn");
        assertEquals(getDomain(columnDescriptor, DOUBLE, 0, null, ID, UTC), all(DOUBLE));

        assertEquals(getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(42.24, 42.24), ID, UTC), singleValue(DOUBLE, 42.24));

        assertEquals(getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(3.3, 42.24), ID, UTC), create(ValueSet.ofRanges(range(DOUBLE, 3.3, true, 42.24, true)), false));

        assertEquals(getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(NaN, NaN), ID, UTC), Domain.notNull(DOUBLE));

        assertEquals(getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(NaN, NaN, true), ID, UTC), Domain.all(DOUBLE));

        assertEquals(getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(3.3, NaN), ID, UTC), Domain.notNull(DOUBLE));

        assertEquals(getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(3.3, NaN, true), ID, UTC), Domain.all(DOUBLE));

        assertEquals(getDomain(DOUBLE, doubleDictionaryDescriptor(NaN)), Domain.all(DOUBLE));

        assertEquals(getDomain(DOUBLE, doubleDictionaryDescriptor(3.3, NaN)), Domain.all(DOUBLE));

        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, DOUBLE, 10, doubleColumnStats(42.24, 3.3), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required double DoubleColumn\" in Parquet file \"testFile\": [min: 42.24, max: 3.3, num_nulls: 0]");
    }

    @Test
    public void testString()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(BINARY, "StringColumn");
        assertEquals(getDomain(columnDescriptor, createUnboundedVarcharType(), 0, null, ID, UTC), all(createUnboundedVarcharType()));

        assertEquals(getDomain(columnDescriptor, createUnboundedVarcharType(), 10, stringColumnStats("taco", "taco"), ID, UTC), singleValue(createUnboundedVarcharType(), utf8Slice("taco")));

        assertEquals(getDomain(columnDescriptor, createUnboundedVarcharType(), 10, stringColumnStats("apple", "taco"), ID, UTC), create(ValueSet.ofRanges(range(createUnboundedVarcharType(), utf8Slice("apple"), true, utf8Slice("taco"), true)), false));

        assertEquals(getDomain(columnDescriptor, createUnboundedVarcharType(), 10, stringColumnStats("中国", "美利坚"), ID, UTC), create(ValueSet.ofRanges(range(createUnboundedVarcharType(), utf8Slice("中国"), true, utf8Slice("美利坚"), true)), false));

        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, createUnboundedVarcharType(), 10, stringColumnStats("taco", "apple"), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required binary StringColumn\" in Parquet file \"testFile\": [min: 0x7461636F, max: 0x6170706C65, num_nulls: 0]");
    }

    private static BinaryStatistics stringColumnStats(String minimum, String maximum)
    {
        BinaryStatistics statistics = new BinaryStatistics();
        statistics.setMinMax(Binary.fromString(minimum), Binary.fromString(maximum));
        return statistics;
    }

    @Test
    public void testFloat()
            throws Exception
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(FLOAT, "FloatColumn");
        assertEquals(getDomain(columnDescriptor, REAL, 0, null, ID, UTC), all(REAL));

        float minimum = 4.3f;
        float maximum = 40.3f;

        assertEquals(getDomain(columnDescriptor, REAL, 10, floatColumnStats(minimum, minimum), ID, UTC), singleValue(REAL, (long) floatToRawIntBits(minimum)));

        assertEquals(
                getDomain(columnDescriptor, REAL, 10, floatColumnStats(minimum, maximum), ID, UTC),
                create(ValueSet.ofRanges(range(REAL, (long) floatToRawIntBits(minimum), true, (long) floatToRawIntBits(maximum), true)), false));

        assertEquals(getDomain(columnDescriptor, REAL, 10, floatColumnStats(NaN, NaN), ID, UTC), Domain.notNull(REAL));

        assertEquals(getDomain(columnDescriptor, REAL, 10, floatColumnStats(NaN, NaN, true), ID, UTC), Domain.all(REAL));

        assertEquals(getDomain(columnDescriptor, REAL, 10, floatColumnStats(minimum, NaN), ID, UTC), Domain.notNull(REAL));

        assertEquals(getDomain(columnDescriptor, REAL, 10, floatColumnStats(minimum, NaN, true), ID, UTC), Domain.all(REAL));

        assertEquals(getDomain(REAL, floatDictionaryDescriptor(NaN)), Domain.all(REAL));

        assertEquals(getDomain(REAL, floatDictionaryDescriptor(minimum, NaN)), Domain.all(REAL));

        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, REAL, 10, floatColumnStats(maximum, minimum), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required float FloatColumn\" in Parquet file \"testFile\": [min: 40.3, max: 4.3, num_nulls: 0]");
    }

    @Test
    public void testDate()
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT32, "DateColumn");
        assertEquals(getDomain(columnDescriptor, DATE, 0, null, ID, UTC), all(DATE));
        assertEquals(getDomain(columnDescriptor, DATE, 10, intColumnStats(100, 100), ID, UTC), singleValue(DATE, 100L));
        assertEquals(getDomain(columnDescriptor, DATE, 10, intColumnStats(0, 100), ID, UTC), create(ValueSet.ofRanges(range(DATE, 0L, true, 100L, true)), false));

        // fail on corrupted statistics
        assertThatExceptionOfType(ParquetCorruptionException.class)
                .isThrownBy(() -> getDomain(columnDescriptor, DATE, 10, intColumnStats(200, 100), ID, UTC))
                .withMessage("Corrupted statistics for column \"[] required int32 DateColumn\" in Parquet file \"testFile\": [min: 200, max: 100, num_nulls: 0]");
    }

    @DataProvider
    public Object[][] timestampPrecision()
    {
        LocalDateTime baseTime = LocalDateTime.of(1970, 1, 19, 10, 28, 52, 123456789);
        return new Object[][] {
                {3, baseTime, baseTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() * MICROSECONDS_PER_MILLISECOND},
                // note the rounding of micros
                {6, baseTime, baseTime.atZone(ZoneOffset.UTC).toInstant().getEpochSecond() * MICROSECONDS_PER_SECOND + 123457},
                {9, baseTime, longTimestamp(9, baseTime)}
        };
    }

    @Test(dataProvider = "timestampPrecision")
    public void testTimestampInt96(int precision, LocalDateTime baseTime, Object baseDomainValue)
            throws ParquetCorruptionException
    {
        ColumnDescriptor columnDescriptor = createColumnDescriptor(INT96, "TimestampColumn");
        TimestampType timestampType = createTimestampType(precision);
        assertEquals(getDomain(columnDescriptor, timestampType, 0, null, ID, UTC), all(timestampType));
        assertEquals(getDomain(columnDescriptor, timestampType, 10, timestampColumnStats(baseTime, baseTime), ID, UTC), singleValue(timestampType, baseDomainValue));
        // INT96 binary ranges ignored when min <> max
        assertEquals(
                getDomain(columnDescriptor, timestampType, 10, timestampColumnStats(baseTime.minusSeconds(10), baseTime), ID, UTC),
                create(ValueSet.all(timestampType), false));
    }

    @Test(dataProvider = "testTimestampInt64DataProvider")
    public void testTimestampInt64(TimeUnit timeUnit, int precision, LocalDateTime baseTime, Object baseDomainValue)
            throws ParquetCorruptionException
    {
        int parquetPrecision;
        switch (timeUnit) {
            case MILLIS:
                parquetPrecision = 3;
                break;
            case MICROS:
                parquetPrecision = 6;
                break;
            case NANOS:
                parquetPrecision = 9;
                break;
            default:
                throw new IllegalArgumentException("Unknown Parquet TimeUnit " + timeUnit);
        }

        PrimitiveType type = Types.required(INT64)
                .as(LogicalTypeAnnotation.timestampType(false, timeUnit))
                .named("TimestampColumn");

        ColumnDescriptor columnDescriptor = new ColumnDescriptor(new String[]{}, type, 0, 0);
        TimestampType timestampType = createTimestampType(precision);
        assertEquals(getDomain(columnDescriptor, timestampType, 0, null, ID, UTC), all(timestampType));
        LocalDateTime maxTime = baseTime.plus(Duration.ofMillis(50));

        Object maxDomainValue;
        if (baseDomainValue instanceof Long) {
            maxDomainValue = (long) baseDomainValue + 50 * MICROSECONDS_PER_MILLISECOND;
        }
        else if (baseDomainValue instanceof LongTimestamp) {
            LongTimestamp longTimestamp = ((LongTimestamp) baseDomainValue);
            maxDomainValue = new LongTimestamp(longTimestamp.getEpochMicros() + 50 * MICROSECONDS_PER_MILLISECOND, longTimestamp.getPicosOfMicro());
        }
        else {
            throw new IllegalArgumentException("Unknown Timestamp domain type " + baseDomainValue);
        }

        long minValue = toEpochWithPrecision(baseTime, parquetPrecision);
        long maxValue = toEpochWithPrecision(maxTime, parquetPrecision);
        assertEquals(getDomain(columnDescriptor, timestampType, 10, longColumnStats(minValue, minValue), ID, UTC), singleValue(timestampType, baseDomainValue));
        assertEquals(getDomain(columnDescriptor, timestampType, 10, longColumnStats(minValue, maxValue), ID, UTC),
                Domain.create(ValueSet.ofRanges(range(timestampType, baseDomainValue, true, maxDomainValue, true)), false));
    }

    @DataProvider
    public Object[][] testTimestampInt64DataProvider()
    {
        LocalDateTime baseTime = LocalDateTime.of(1970, 1, 19, 10, 28, 52, 123456789);
        Object millisExpectedValue = baseTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() * MICROSECONDS_PER_MILLISECOND;
        // note the rounding of micros
        Object microsExpectedValue = baseTime.atZone(ZoneOffset.UTC).toInstant().getEpochSecond() * MICROSECONDS_PER_SECOND + 123457;
        Object nanosExpectedValue = longTimestamp(9, baseTime);

        Object nanosTruncatedToMillisExpectedValue = longTimestamp(
                9,
                LocalDateTime.of(1970, 1, 19, 10, 28, 52, 123000000));
        Object nanosTruncatedToMicrosExpectedValue = longTimestamp(
                9,
                LocalDateTime.of(1970, 1, 19, 10, 28, 52, 123457000));
        return new Object[][] {
                {TimeUnit.MILLIS, 3, baseTime, millisExpectedValue},
                {TimeUnit.MICROS, 3, baseTime, millisExpectedValue},
                {TimeUnit.NANOS, 3, baseTime, millisExpectedValue},
                {TimeUnit.MILLIS, 6, baseTime, millisExpectedValue},
                {TimeUnit.MICROS, 6, baseTime, microsExpectedValue},
                {TimeUnit.NANOS, 6, baseTime, microsExpectedValue},
                {TimeUnit.MILLIS, 9, baseTime, nanosTruncatedToMillisExpectedValue},
                {TimeUnit.MICROS, 9, baseTime, nanosTruncatedToMicrosExpectedValue},
                {TimeUnit.NANOS, 9, baseTime, nanosExpectedValue},
        };
    }

    private static long toEpochWithPrecision(LocalDateTime time, int precision)
    {
        long scaledEpochSeconds = time.toEpochSecond(ZoneOffset.UTC) * (long) Math.pow(10, precision);
        long fractionOfSecond = LongMath.divide(time.getNano(), (long) Math.pow(10, 9 - precision), RoundingMode.HALF_UP);
        return scaledEpochSeconds + fractionOfSecond;
    }

    private static BinaryStatistics timestampColumnStats(LocalDateTime minimum, LocalDateTime maximum)
    {
        BinaryStatistics statistics = new BinaryStatistics();
        statistics.setMinMax(Binary.fromConstantByteArray(toParquetEncoding(minimum)), Binary.fromConstantByteArray(toParquetEncoding(maximum)));
        return statistics;
    }

    private static byte[] toParquetEncoding(LocalDateTime timestamp)
    {
        long startOfDay = timestamp.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long timeOfDayNanos = (long) ((timestamp.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() - startOfDay) * Math.pow(10, 6)) + timestamp.getNano() % NANOSECONDS_PER_MILLISECOND;

        Slice slice = Slices.allocate(12);
        slice.setLong(0, timeOfDayNanos);
        slice.setInt(8, millisToJulianDay(timestamp.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()));
        return slice.byteArray();
    }

    private static int millisToJulianDay(long timestamp)
    {
        return toIntExact(MILLISECONDS.toDays(timestamp) + JULIAN_EPOCH_OFFSET_DAYS);
    }

    @Test
    public void testVarcharMatchesWithStatistics()
            throws ParquetCorruptionException
    {
        String value = "Test";
        ColumnDescriptor columnDescriptor = createColumnDescriptor(BINARY, "VarcharColumn");
        RichColumnDescriptor column = new RichColumnDescriptor(columnDescriptor, new PrimitiveType(OPTIONAL, BINARY, "Test column"));
        TupleDomain<ColumnDescriptor> effectivePredicate = getEffectivePredicate(column, createVarcharType(255), utf8Slice(value));
        TupleDomainParquetPredicate parquetPredicate = new TupleDomainParquetPredicate(effectivePredicate, singletonList(column), UTC);
        Statistics<?> stats = getStatsBasedOnType(column.getPrimitiveType().getPrimitiveTypeName());
        stats.setNumNulls(1L);
        stats.setMinMaxFromBytes(value.getBytes(UTF_8), value.getBytes(UTF_8));
        assertTrue(parquetPredicate.matches(2, ImmutableMap.of(column, stats), ID));
    }

    @Test(dataProvider = "typeForParquetInt32")
    public void testIntegerMatchesWithStatistics(Type typeForParquetInt32)
            throws ParquetCorruptionException
    {
        RichColumnDescriptor column = new RichColumnDescriptor(
                createColumnDescriptor(INT32, "Test column"),
                new PrimitiveType(OPTIONAL, INT32, "Test column"));
        TupleDomain<ColumnDescriptor> effectivePredicate = TupleDomain.withColumnDomains(ImmutableMap.of(
                column,
                Domain.create(ValueSet.of(typeForParquetInt32, 42L, 43L, 44L, 112L), false)));
        TupleDomainParquetPredicate parquetPredicate = new TupleDomainParquetPredicate(effectivePredicate, singletonList(column), UTC);

        assertTrue(parquetPredicate.matches(2, ImmutableMap.of(column, intColumnStats(32, 42)), ID));
        assertFalse(parquetPredicate.matches(2, ImmutableMap.of(column, intColumnStats(30, 40)), ID));
        assertEquals(parquetPredicate.matches(2, ImmutableMap.of(column, intColumnStats(1024, 0x10000 + 42)), ID), (typeForParquetInt32 != INTEGER)); // stats invalid for smallint/tinyint
    }

    @DataProvider
    public Object[][] typeForParquetInt32()
    {
        return new Object[][] {
                {INTEGER},
                {SMALLINT},
                {TINYINT},
        };
    }

    @Test
    public void testBigintMatchesWithStatistics()
            throws ParquetCorruptionException
    {
        RichColumnDescriptor column = new RichColumnDescriptor(
                new ColumnDescriptor(new String[] {"path"}, INT64, 0, 0),
                new PrimitiveType(OPTIONAL, INT64, "Test column"));
        TupleDomain<ColumnDescriptor> effectivePredicate = TupleDomain.withColumnDomains(ImmutableMap.of(
                column,
                Domain.create(ValueSet.of(BIGINT, 42L, 43L, 44L, 404L), false)));
        TupleDomainParquetPredicate parquetPredicate = new TupleDomainParquetPredicate(effectivePredicate, singletonList(column), UTC);

        assertTrue(parquetPredicate.matches(2, ImmutableMap.of(column, longColumnStats(32, 42)), ID));
        assertFalse(parquetPredicate.matches(2, ImmutableMap.of(column, longColumnStats(30, 40)), ID));
        assertFalse(parquetPredicate.matches(2, ImmutableMap.of(column, longColumnStats(1024, 0x10000 + 42)), ID));
    }

    @Test
    public void testVarcharMatchesWithDictionaryDescriptor()
    {
        ColumnDescriptor columnDescriptor = new ColumnDescriptor(new String[] {"path"}, BINARY, 0, 0);
        RichColumnDescriptor column = new RichColumnDescriptor(columnDescriptor, new PrimitiveType(OPTIONAL, BINARY, "Test column"));
        TupleDomain<ColumnDescriptor> effectivePredicate = getEffectivePredicate(column, createVarcharType(255), EMPTY_SLICE);
        TupleDomainParquetPredicate parquetPredicate = new TupleDomainParquetPredicate(effectivePredicate, singletonList(column), UTC);
        DictionaryPage page = new DictionaryPage(Slices.wrappedBuffer(new byte[] {0, 0, 0, 0}), 1, PLAIN_DICTIONARY);
        assertTrue(parquetPredicate.matches(new DictionaryDescriptor(column, Optional.of(page))));
    }

    private ColumnDescriptor createColumnDescriptor(PrimitiveTypeName typeName, String columnName)
    {
        return new ColumnDescriptor(new String[]{}, new PrimitiveType(REQUIRED, typeName, columnName), 0, 0);
    }

    private TupleDomain<ColumnDescriptor> getEffectivePredicate(RichColumnDescriptor column, VarcharType type, Slice value)
    {
        ColumnDescriptor predicateColumn = new ColumnDescriptor(column.getPath(), column.getPrimitiveType().getPrimitiveTypeName(), 0, 0);
        Domain predicateDomain = singleValue(type, value);
        Map<ColumnDescriptor, Domain> predicateColumns = singletonMap(predicateColumn, predicateDomain);
        return withColumnDomains(predicateColumns);
    }

    private static FloatStatistics floatColumnStats(float minimum, float maximum)
    {
        return floatColumnStats(minimum, maximum, false);
    }

    private static FloatStatistics floatColumnStats(float minimum, float maximum, boolean hasNulls)
    {
        FloatStatistics statistics = new FloatStatistics();
        statistics.setMinMax(minimum, maximum);
        if (hasNulls) {
            statistics.setNumNulls(1);
        }
        return statistics;
    }

    private static DoubleStatistics doubleColumnStats(double minimum, double maximum)
    {
        return doubleColumnStats(minimum, maximum, false);
    }

    private static DoubleStatistics doubleColumnStats(double minimum, double maximum, boolean hasNulls)
    {
        DoubleStatistics statistics = new DoubleStatistics();
        statistics.setMinMax(minimum, maximum);
        if (hasNulls) {
            statistics.setNumNulls(1);
        }
        return statistics;
    }

    private static IntStatistics intColumnStats(int minimum, int maximum)
    {
        IntStatistics statistics = new IntStatistics();
        statistics.setMinMax(minimum, maximum);
        return statistics;
    }

    private static LongStatistics longColumnStats(long minimum, long maximum)
    {
        LongStatistics statistics = new LongStatistics();
        statistics.setMinMax(minimum, maximum);
        return statistics;
    }

    private static BinaryStatistics binaryColumnStats(long minimum, long maximum)
    {
        return binaryColumnStats(BigInteger.valueOf(minimum), BigInteger.valueOf(maximum));
    }

    private static BinaryStatistics binaryColumnStats(BigInteger minimum, BigInteger maximum)
    {
        BinaryStatistics statistics = new BinaryStatistics();
        statistics.setMinMax(
                Binary.fromConstantByteArray(minimum.toByteArray()),
                Binary.fromConstantByteArray(maximum.toByteArray()));
        return statistics;
    }

    private static LongStatistics longOnlyNullsStats(long numNulls)
    {
        LongStatistics statistics = new LongStatistics();
        statistics.setNumNulls(numNulls);
        return statistics;
    }

    private DictionaryDescriptor floatDictionaryDescriptor(float... values)
            throws Exception
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(buf)) {
            for (float val : values) {
                out.writeFloat(val);
            }
        }
        return new DictionaryDescriptor(
                new ColumnDescriptor(new String[] {"dummy"}, new PrimitiveType(OPTIONAL, PrimitiveType.PrimitiveTypeName.FLOAT, 0, ""), 1, 1),
                Optional.of(new DictionaryPage(Slices.wrappedBuffer(buf.toByteArray()), values.length, PLAIN_DICTIONARY)));
    }

    private DictionaryDescriptor doubleDictionaryDescriptor(double... values)
            throws Exception
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(buf)) {
            for (double val : values) {
                out.writeDouble(val);
            }
        }
        return new DictionaryDescriptor(
                new ColumnDescriptor(new String[] {"dummy"}, new PrimitiveType(OPTIONAL, PrimitiveType.PrimitiveTypeName.DOUBLE, 0, ""), 1, 1),
                Optional.of(new DictionaryPage(Slices.wrappedBuffer(buf.toByteArray()), values.length, PLAIN_DICTIONARY)));
    }

    private static LongTimestamp longTimestamp(long precision, LocalDateTime start)
    {
        checkArgument(precision > MAX_SHORT_PRECISION && precision <= TimestampType.MAX_PRECISION, "Precision is out of range");
        return new LongTimestamp(
                start.atZone(ZoneOffset.UTC).toInstant().getEpochSecond() * MICROSECONDS_PER_SECOND + start.getLong(MICRO_OF_SECOND),
                toIntExact(round((start.getNano() % PICOSECONDS_PER_NANOSECOND) * PICOSECONDS_PER_NANOSECOND, toIntExact(TimestampType.MAX_PRECISION - precision))));
    }
}
