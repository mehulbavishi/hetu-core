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
package io.prestosql.plugin.hive.orc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.tpch.Nation;
import io.airlift.tpch.NationColumn;
import io.airlift.tpch.NationGenerator;
import io.prestosql.metadata.Metadata;
import io.prestosql.orc.OrcCacheStore;
import io.prestosql.plugin.hive.DeleteDeltaLocations;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveConfig;
import io.prestosql.plugin.hive.HivePageSourceFactory;
import io.prestosql.plugin.hive.HiveTestUtils;
import io.prestosql.plugin.hive.HiveTypeTranslator;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.type.InternalTypeManager;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.testng.annotations.Test;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.function.LongPredicate;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.tpch.NationColumn.COMMENT;
import static io.airlift.tpch.NationColumn.NAME;
import static io.airlift.tpch.NationColumn.NATION_KEY;
import static io.airlift.tpch.NationColumn.REGION_KEY;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.prestosql.plugin.hive.HiveStorageFormat.ORC;
import static io.prestosql.plugin.hive.HiveType.toHiveType;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.util.Collections.nCopies;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.FILE_INPUT_FORMAT;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.TABLE_IS_TRANSACTIONAL;
import static org.apache.hadoop.hive.ql.io.AcidUtils.deleteDeltaSubdir;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_LIB;
import static org.testng.Assert.assertEquals;

public class TestOrcAcidPageSource
{
    private static final Metadata METADATA = createTestMetadataManager();
    public static final TypeManager TYPE_MANAGER = new InternalTypeManager(METADATA.getFunctionAndTypeManager());

    private static final HivePageSourceFactory PAGE_SOURCE_FACTORY = new OrcPageSourceFactory(
            TYPE_MANAGER,
            new HiveConfig().setUseOrcColumnNames(false),
            HiveTestUtils.createTestHdfsEnvironment(new HiveConfig()),
            new FileFormatDataSourceStats(), OrcCacheStore.builder().newCacheStore(
            new HiveConfig().getOrcFileTailCacheLimit(), Duration.ofMillis(new HiveConfig().getOrcFileTailCacheTtl().toMillis()),
            new HiveConfig().getOrcStripeFooterCacheLimit(),
            Duration.ofMillis(new HiveConfig().getOrcStripeFooterCacheTtl().toMillis()),
            new HiveConfig().getOrcRowIndexCacheLimit(), Duration.ofMillis(new HiveConfig().getOrcRowIndexCacheTtl().toMillis()),
            new HiveConfig().getOrcBloomFiltersCacheLimit(),
            Duration.ofMillis(new HiveConfig().getOrcBloomFiltersCacheTtl().toMillis()),
            new HiveConfig().getOrcRowDataCacheMaximumWeight(), Duration.ofMillis(new HiveConfig().getOrcRowDataCacheTtl().toMillis()),
            new HiveConfig().isOrcCacheStatsMetricCollectionEnabled()));

    @Test
    public void testFullFileRead()
    {
        assertRead(ImmutableSet.copyOf(NationColumn.values()), OptionalLong.empty(), Optional.empty(), nationKey -> false);
    }

    @Test
    public void testSingleColumnRead()
    {
        assertRead(ImmutableSet.of(REGION_KEY), OptionalLong.empty(), Optional.empty(), nationKey -> false);
    }

    /**
     * tests file stats based pruning works fine
     */
    @Test
    public void testFullFileSkipped()
    {
        assertRead(ImmutableSet.copyOf(NationColumn.values()), OptionalLong.of(100L), Optional.empty(), nationKey -> false);
    }

    /**
     * Tests stripe stats and row groups stats based pruning works fine
     */
    @Test
    public void testSomeStripesAndRowGroupRead()
    {
        assertRead(ImmutableSet.copyOf(NationColumn.values()), OptionalLong.of(5L), Optional.empty(), nationKey -> false);
    }

    @Test
    public void testDeletedRows()
    {
        Path partitionLocation = new Path(getClass().getClassLoader().getResource("nation_delete_deltas") + "/");
        Optional<DeleteDeltaLocations> deleteDeltaLocations = DeleteDeltaLocations.builder(partitionLocation)
                .addDeleteDelta(new Path(partitionLocation, deleteDeltaSubdir(3L, 3L, 0)), 3L, 3L, 0)
                .addDeleteDelta(new Path(partitionLocation, deleteDeltaSubdir(4L, 4L, 0)), 4L, 4L, 0)
                .build();

        assertRead(ImmutableSet.copyOf(NationColumn.values()), OptionalLong.empty(), deleteDeltaLocations, nationKey -> nationKey == 5 || nationKey == 19);
    }

    private static void assertRead(Set<NationColumn> columns, OptionalLong nationKeyPredicate, Optional<DeleteDeltaLocations> deleteDeltaLocations, LongPredicate deletedRows)
    {
        TupleDomain<HiveColumnHandle> tupleDomain = TupleDomain.all();
        if (nationKeyPredicate.isPresent()) {
            tupleDomain = TupleDomain.withColumnDomains(ImmutableMap.of(toHiveColumnHandle(NATION_KEY), Domain.singleValue(BIGINT, nationKeyPredicate.getAsLong())));
        }

        List<Nation> actual = readFile(columns, tupleDomain, deleteDeltaLocations);

        List<Nation> expected = new ArrayList<>();
        for (Nation nation : ImmutableList.copyOf(new NationGenerator().iterator())) {
            if (nationKeyPredicate.isPresent() && nationKeyPredicate.getAsLong() != nation.getNationKey()) {
                continue;
            }
            if (deletedRows.test(nation.getNationKey())) {
                continue;
            }
            expected.addAll(nCopies(1000, nation));
        }

        assertEqualsByColumns(columns, actual, expected);
    }

    private static List<Nation> readFile(Set<NationColumn> columns, TupleDomain<HiveColumnHandle> tupleDomain, Optional<DeleteDeltaLocations> deleteDeltaLocations)
    {
        List<HiveColumnHandle> columnHandles = columns.stream()
                .map(TestOrcAcidPageSource::toHiveColumnHandle)
                .collect(toImmutableList());

        List<String> columnNames = columnHandles.stream()
                .map(HiveColumnHandle::getName)
                .collect(toImmutableList());

        // This file has the contains the TPC-H nation table which each row repeated 1000 times
        File nationFileWithReplicatedRows = new File(TestOrcAcidPageSource.class.getClassLoader().getResource("nationFile25kRowsSortedOnNationKey/bucket_00000").getPath());

        ConnectorPageSource pageSource = PAGE_SOURCE_FACTORY.createPageSource(
                new JobConf(new Configuration(false)),
                HiveTestUtils.SESSION,
                new Path(nationFileWithReplicatedRows.getAbsoluteFile().toURI()),
                0,
                nationFileWithReplicatedRows.length(),
                nationFileWithReplicatedRows.length(),
                createSchema(),
                columnHandles,
                tupleDomain,
                Optional.empty(),
                deleteDeltaLocations,
                Optional.empty(),
                Optional.empty(),
                null,
                false, -1L).get();

        int nationKeyColumn = columnNames.indexOf("n_nationkey");
        int nameColumn = columnNames.indexOf("n_name");
        int regionKeyColumn = columnNames.indexOf("n_regionkey");
        int commentColumn = columnNames.indexOf("n_comment");

        ImmutableList.Builder<Nation> rows = ImmutableList.builder();
        while (!pageSource.isFinished()) {
            Page page = pageSource.getNextPage();
            if (page == null) {
                continue;
            }

            page = page.getLoadedPage();
            for (int position = 0; position < page.getPositionCount(); position++) {
                long nationKey = -42;
                if (nationKeyColumn >= 0) {
                    nationKey = BIGINT.getLong(page.getBlock(nationKeyColumn), position);
                }

                String name = "<not read>";
                if (nameColumn >= 0) {
                    name = VARCHAR.getSlice(page.getBlock(nameColumn), position).toStringUtf8();
                }

                long regionKey = -42;
                if (regionKeyColumn >= 0) {
                    regionKey = BIGINT.getLong(page.getBlock(regionKeyColumn), position);
                }

                String comment = "<not read>";
                if (commentColumn >= 0) {
                    comment = VARCHAR.getSlice(page.getBlock(commentColumn), position).toStringUtf8();
                }

                rows.add(new Nation(position, nationKey, name, regionKey, comment));
            }
        }
        return rows.build();
    }

    private static HiveColumnHandle toHiveColumnHandle(NationColumn nationColumn)
    {
        Type prestoType;
        switch (nationColumn.getType().getBase()) {
            case IDENTIFIER:
            case INTEGER:
                prestoType = BIGINT;
                break;
            case VARCHAR:
                prestoType = VARCHAR;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + nationColumn.getType().getBase());
        }

        return new HiveColumnHandle(
                nationColumn.getColumnName(),
                toHiveType(new HiveTypeTranslator(), prestoType),
                prestoType.getTypeSignature(),
                0,
                REGULAR,
                Optional.empty());
    }

    private static Properties createSchema()
    {
        Properties schema = new Properties();
        schema.setProperty(SERIALIZATION_LIB, ORC.getSerDe());
        schema.setProperty(FILE_INPUT_FORMAT, ORC.getInputFormat());
        schema.setProperty(TABLE_IS_TRANSACTIONAL, "true");
        return schema;
    }

    private static void assertEqualsByColumns(Set<NationColumn> columns, List<Nation> actualRows, List<Nation> expectedRows)
    {
        assertEquals(actualRows.size(), expectedRows.size(), "row count");
        for (int i = 0; i < actualRows.size(); i++) {
            Nation actual = actualRows.get(i);
            Nation expected = expectedRows.get(i);
            assertEquals(actual.getNationKey(), columns.contains(NATION_KEY) ? expected.getNationKey() : -42);
            assertEquals(actual.getName(), columns.contains(NAME) ? expected.getName() : "<not read>");
            assertEquals(actual.getRegionKey(), columns.contains(REGION_KEY) ? expected.getRegionKey() : -42);
            assertEquals(actual.getComment(), columns.contains(COMMENT) ? expected.getComment() : "<not read>");
        }
    }
}
