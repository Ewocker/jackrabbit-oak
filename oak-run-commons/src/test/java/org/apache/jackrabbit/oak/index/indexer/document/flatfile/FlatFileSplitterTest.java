package org.apache.jackrabbit.oak.index.indexer.document.flatfile;

import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.oak.InitialContent;
import org.apache.jackrabbit.oak.OakInitializer;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.index.indexer.document.NodeStateEntry;
import org.apache.jackrabbit.oak.plugins.index.search.IndexDefinition;
import org.apache.jackrabbit.oak.plugins.index.search.util.IndexDefinitionBuilder;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.plugins.name.NamespaceEditorProvider;
import org.apache.jackrabbit.oak.plugins.nodetype.TypeEditorProvider;
import org.apache.jackrabbit.oak.query.ast.NodeTypeInfo;
import org.apache.jackrabbit.oak.query.ast.NodeTypeInfoProvider;
import org.apache.jackrabbit.oak.spi.blob.MemoryBlobStore;
import org.apache.jackrabbit.oak.spi.commit.CompositeEditorProvider;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Stream;

import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.NT_BASE;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileStoreUtils.createReader;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileStoreUtils.createWriter;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlatFileSplitterTest {
    private ClassLoader classLoader = getClass().getClassLoader();
    private MemoryBlobStore store = new MemoryBlobStore();
    private NodeStateEntryReader entryReader = new NodeStateEntryReader(store);
    private final int maxSplitSize = Integer.MAX_VALUE;
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void ntBaseSkipSplit() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        splitNodeTypeNames.add(NT_BASE);
        File flatFile = new File(classLoader.getResource("simple-split.json").getFile());
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, null, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(1, flatFileList.size());
        assertEquals(flatFile, flatFileList.get(0));
    }

    @Test
    public void belowThresholdSkipSplit() throws IOException {
        File flatFile = new File(classLoader.getResource("simple-split.json").getFile());
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, null, null, null, entryReader, null, new HashSet<>(), Integer.MAX_VALUE, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(1, flatFileList.size());
        assertEquals(flatFile, flatFileList.get(0));
    }

    @Test
    public void unknownTypeNoSplit() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        File flatFile = new File(classLoader.getResource("unknown-no-split.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(1, flatFileList.size());
        assertEquals(flatFile.length(), flatFileList.get(0).length());
    }

    @Test
    public void deleteAfterSplit() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        File flatFile = new File(classLoader.getResource("simple-split.json").getFile());
        File copied = new File(temporaryFolder.newFile().getAbsolutePath());
        FileUtils.copyFile(flatFile, copied);
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        FlatFileSplitter splitter = new FlatFileSplitter(copied, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);

        long originalSize = flatFile.length();
        List<File> flatFileList = splitter.split();

        assertEquals(originalSize, getTotalSize(flatFileList));
        assertTrue(flatFile.exists());
        assertTrue(!copied.exists());
    }

    @Test
    public void simpleSplit() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        File flatFile = new File(classLoader.getResource("simple-split.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(3, flatFileList.size());
        assertEquals(1, countLines(flatFileList.get(0)));
        assertEquals(1, countLines(flatFileList.get(1)));
        assertEquals(1, countLines(flatFileList.get(2)));
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
    }

    @Test
    public void simpleSplitWithParent() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        splitNodeTypeNames.add("no-split");
        File flatFile = new File(classLoader.getResource("simple-split-with-parent.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(2, flatFileList.size());
        assertEquals(4, countLines(flatFileList.get(0)));
        assertEquals("no-split", startLineType(flatFileList.get(0)));
        assertEquals(4, countLines(flatFileList.get(1)));
        assertEquals("no-split", startLineType(flatFileList.get(1)));
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
    }

    @Test
    public void simpleSplitWithNestedParent() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        splitNodeTypeNames.add("no-split");
        File flatFile = new File(classLoader.getResource("simple-split-with-nested-parent.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        int splitSize = Integer.MAX_VALUE; // split whenever possible
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, splitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(4, flatFileList.size());
        assertEquals(2, countLines(flatFileList.get(0)));
        assertEquals("no-split", startLineType(flatFileList.get(0)));
        assertEquals(4, countLines(flatFileList.get(1)));
        assertEquals("no-split", startLineType(flatFileList.get(1)));
        assertEquals(1, countLines(flatFileList.get(2)));
        assertEquals("split", startLineType(flatFileList.get(2)));
        assertEquals(2, countLines(flatFileList.get(3)));
        assertEquals("no-split", startLineType(flatFileList.get(3)));
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
    }

    @Test
    public void multipleNodeTypeSplitWithParent() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        splitNodeTypeNames.add("no-split-1");
        splitNodeTypeNames.add("no-split-2");
        File flatFile = new File(classLoader.getResource("multiple-node-type-simple-split-with-parent.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(4, flatFileList.size());
        assertEquals(2, countLines(flatFileList.get(0)));
        assertEquals("no-split-1", startLineType(flatFileList.get(0)));
        assertEquals(2, countLines(flatFileList.get(1)));
        assertEquals("no-split-2", startLineType(flatFileList.get(1)));
        assertEquals(1, countLines(flatFileList.get(2)));
        assertEquals(1, countLines(flatFileList.get(3)));
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
    }

    @Test
    public void multipleNodeTypeSplitWithNestedParent() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        splitNodeTypeNames.add("no-split-1");
        splitNodeTypeNames.add("no-split-2");
        splitNodeTypeNames.add("no-split-3");
        splitNodeTypeNames.add("no-split-4");
        File flatFile = new File(classLoader.getResource("multiple-node-type-simple-split-with-nested-parent.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, maxSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(4, flatFileList.size());
        assertEquals(2, countLines(flatFileList.get(0)));
        assertEquals("no-split-1", startLineType(flatFileList.get(0)));
        assertEquals(4, countLines(flatFileList.get(1)));
        assertEquals("no-split-2", startLineType(flatFileList.get(1)));
        assertEquals(1, countLines(flatFileList.get(2)));
        assertEquals("split", startLineType(flatFileList.get(2)));
        assertEquals(2, countLines(flatFileList.get(3)));
        assertEquals("no-split-4", startLineType(flatFileList.get(3)));
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
    }

    @Test
    public void splitAsset() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>();
        String assetNodeType = "dam:Asset";
        splitNodeTypeNames.add(assetNodeType);
        File flatFile = new File(classLoader.getResource("complex-split.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        int expectedSplitSize = 10;
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, expectedSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(expectedSplitSize, flatFileList.size());
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
        assertEquals(startLine(flatFile), startLine(flatFileList.get(0)));
        for (int i = 1; i < flatFileList.size(); i++) {
            assertEquals(assetNodeType, startLineType(flatFileList.get(i)));
        }
    }

    @Test
    public void splitFolder() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>(Arrays.asList(
                "nt:file",
                "cq:VirtualComponent",
                "nt:folder",
                "cq:PollConfigFolder",
                "cq:ExporterConfigFolder",
                "cq:ClientLibraryFolder",
                "cq:ComponentMixin",
                "cq:ContentSyncConfig",
                "cq:Component",
                "sling:OrderedFolder",
                "sling:Folder",
                "granite:Component"));
        File flatFile = new File(classLoader.getResource("complex-split.json").getFile());
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        int expectedSplitSize = 2;
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, expectedSplitSize, false);
        List<File> flatFileList = splitter.split(false);

        assertEquals(expectedSplitSize, flatFileList.size());
        assertEquals(flatFile.length(), getTotalSize(flatFileList));
        assertEquals(startLineType(flatFile), startLineType(flatFileList.get(0)));
        String expectedSplitPoint = "/etc|{\"jcr:created\":\"dat:2021-05-27T19:04:57.948Z\",\"jcr:mixinTypes\":[\"nam:rep:AccessControllable\"],\"jcr:createdBy\":\"admin\",\"jcr:primaryType\":\"nam:sling:Folder\"}";
        assertEquals(expectedSplitPoint, startLine(flatFileList.get(1)));
    }

    @Test
    public void splitFolderWithCompression() throws IOException {
        Set<String> splitNodeTypeNames = new HashSet<>(Arrays.asList(
                "nt:file",
                "cq:VirtualComponent",
                "nt:folder",
                "cq:PollConfigFolder",
                "cq:ExporterConfigFolder",
                "cq:ClientLibraryFolder",
                "cq:ComponentMixin",
                "cq:ContentSyncConfig",
                "cq:Component",
                "sling:OrderedFolder",
                "sling:Folder",
                "granite:Component"));
        File rawFlatFile = new File(classLoader.getResource("complex-split.json").getFile());;
        File flatFile = temporaryFolder.newFile();
        compress(rawFlatFile, flatFile);
        String workDirPath = temporaryFolder.newFolder().getAbsolutePath();
        int expectedSplitSize = 2;
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, entryReader, null, splitNodeTypeNames, 0, expectedSplitSize, true);
        List<File> flatFileList = splitter.split(false);
        List<File> rawFlatFileList = new ArrayList<>();

        for (File f: flatFileList) {
            File uf = temporaryFolder.newFile();
            uncompress(f, uf);
            rawFlatFileList.add(uf);
        }

        assertEquals(expectedSplitSize, flatFileList.size());
        assertEquals(rawFlatFile.length(), getTotalSize(rawFlatFileList));
    }

    @Test
    public void getSplitNodeTypeNames() {
        NodeStore store = new MemoryNodeStore();
        EditorHook hook = new EditorHook(
                new CompositeEditorProvider(new NamespaceEditorProvider(), new TypeEditorProvider()));
        OakInitializer.initialize(store, new InitialContent(), hook);

        Set<IndexDefinition> defns = new HashSet<>();

        IndexDefinitionBuilder defnb1 = new IndexDefinitionBuilder();
        defnb1.indexRule("testIndexRule1");
        defnb1.aggregateRule("testAggregate1");
        IndexDefinition defn1 = IndexDefinition.newBuilder(store.getRoot(), defnb1.build(), "/foo").build();
        defns.add(defn1);

        IndexDefinitionBuilder defnb2 = new IndexDefinitionBuilder();
        defnb2.indexRule("testIndexRule2");
        defnb2.aggregateRule("testAggregate2");
        defnb2.aggregateRule("testAggregate3");
        IndexDefinition defn2 = IndexDefinition.newBuilder(store.getRoot(), defnb2.build(), "/bar").build();
        defns.add(defn2);

        List<String> resultNodeTypes = new ArrayList<>();
        NodeTypeInfoProvider mockNodeTypeInfoProvider = Mockito.mock(NodeTypeInfoProvider.class);
        for (String nodeType: new ArrayList<String>(Arrays.asList(
                "testIndexRule1",
                "testIndexRule2",
                "testAggregate1",
                "testAggregate2",
                "testAggregate3"
        ))) {
            NodeTypeInfo mockNodeTypeInfo = Mockito.mock(NodeTypeInfo.class, nodeType);
            Mockito.when(mockNodeTypeInfo.getNodeTypeName()).thenReturn(nodeType);
            Mockito.when(mockNodeTypeInfoProvider.getNodeTypeInfo(nodeType)).thenReturn(mockNodeTypeInfo);

            String primary1 = nodeType + "TestPrimarySubType";
            Set<String> primary = new HashSet<>(Arrays.asList(primary1));
            Mockito.when(mockNodeTypeInfo.getPrimarySubTypes()).thenReturn(primary);
            NodeTypeInfo mockPrimary = Mockito.mock(NodeTypeInfo.class, nodeType);
            Mockito.when(mockPrimary.getPrimarySubTypes()).thenReturn(new HashSet<>());
            Mockito.when(mockPrimary.getMixinSubTypes()).thenReturn(new HashSet<>());
            Mockito.when(mockPrimary.getNodeTypeName()).thenReturn(primary1);
            Mockito.when(mockNodeTypeInfoProvider.getNodeTypeInfo(primary1)).thenReturn(mockPrimary);

            String mixin1 = nodeType+"TestMixinSubType1";
            String mixin2 = nodeType+"TestMixinSubType2";
            Set<String> mixin = new HashSet<>(Arrays.asList(mixin1, mixin2));
            Mockito.when(mockNodeTypeInfo.getMixinSubTypes()).thenReturn(mixin);
            NodeTypeInfo mockMixin1 = Mockito.mock(NodeTypeInfo.class, nodeType);
            Mockito.when(mockMixin1.getPrimarySubTypes()).thenReturn(new HashSet<>());
            Mockito.when(mockMixin1.getMixinSubTypes()).thenReturn(new HashSet<>());
            Mockito.when(mockMixin1.getNodeTypeName()).thenReturn(mixin1);
            Mockito.when(mockNodeTypeInfoProvider.getNodeTypeInfo(mixin1)).thenReturn(mockMixin1);
            NodeTypeInfo mockMixin2 = Mockito.mock(NodeTypeInfo.class, nodeType);
            Mockito.when(mockMixin2.getPrimarySubTypes()).thenReturn(new HashSet<>());
            Mockito.when(mockMixin2.getMixinSubTypes()).thenReturn(new HashSet<>());
            Mockito.when(mockMixin2.getNodeTypeName()).thenReturn(mixin2);
            Mockito.when(mockNodeTypeInfoProvider.getNodeTypeInfo(mixin2)).thenReturn(mockMixin2);


            resultNodeTypes.add(nodeType);
            resultNodeTypes.addAll(primary);
            resultNodeTypes.addAll(mixin);
        }
        assertEquals("test setup incorrectly", resultNodeTypes.size(), 20);

        FlatFileSplitter splitter = new FlatFileSplitter(null, null, null, mockNodeTypeInfoProvider, null, defns, null, 0, 0, false);
        Set<String> nodeTypes = splitter.getSplitNodeTypeNames();

        assertEquals(resultNodeTypes.size(), nodeTypes.size()); // exclude unknown node type
        assertTrue(nodeTypes.containsAll(resultNodeTypes));
    }

    @Test
    public void failCreatWorkDir() throws IOException {
        String workDirPath = temporaryFolder.newFile().getAbsolutePath();
        File flatFile = new File(classLoader.getResource("simple-split.json").getFile());
        FlatFileSplitter splitter = new FlatFileSplitter(flatFile, workDirPath, null, null, null, null, null, 0, maxSplitSize, false);
        List<File> flatFileList;
        flatFileList = splitter.split(false);

        assertEquals(flatFile, flatFileList.get(0));
    }

    public int countLines(File file) throws IOException {
        try (Stream<String> stream = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            return (int) stream.count();
        }
    }

    public String startLine(File file) throws IOException {
        try (Scanner scanner = new Scanner(file)) {
            return scanner.nextLine();
        }
    }

    public String startLineType(File file) throws IOException {
        NodeStateEntry nse = entryReader.read(startLine(file));
        PropertyState property = nse.getNodeState().getProperty(JCR_PRIMARYTYPE);
        return property.getValue(Type.STRING);
    }

    public void printFile(File file) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }
    }

    public long getTotalSize(List<File> flatFileList) {
        long totalFileSize = 0;
        for (File f: flatFileList) {
            totalFileSize += f.length();
        }
        return totalFileSize;
    }

    public void compress(File src, File dest) throws IOException {
        try (BufferedReader r = new BufferedReader(createReader(src, false));
             BufferedWriter w = new BufferedWriter(createWriter(dest, true))) {
            String line;
            while ((line = r.readLine()) != null) {
                w.write(line);
                w.newLine();
            }
        }
    }

    public void uncompress(File src, File dest) throws IOException {
        try (BufferedReader r = new BufferedReader(createReader(src, true));
             BufferedWriter w = new BufferedWriter(createWriter(dest, false))) {
            String line;
            while ((line = r.readLine()) != null) {
                w.write(line);
                w.newLine();
            }
        }
    }
}