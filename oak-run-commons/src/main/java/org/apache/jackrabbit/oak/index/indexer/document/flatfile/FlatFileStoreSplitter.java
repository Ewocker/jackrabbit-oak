package org.apache.jackrabbit.oak.index.indexer.document.flatfile;

import com.google.common.base.Joiner;
import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.index.IndexHelper;
import org.apache.jackrabbit.oak.index.IndexerSupport;
import org.apache.jackrabbit.oak.index.indexer.document.NodeStateEntry;
import org.apache.jackrabbit.oak.plugins.index.search.Aggregate;
import org.apache.jackrabbit.oak.plugins.index.search.IndexDefinition;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.query.NodeStateNodeTypeInfoProvider;
import org.apache.jackrabbit.oak.query.ast.NodeTypeInfo;
import org.apache.jackrabbit.oak.query.ast.NodeTypeInfoProvider;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import static com.google.common.base.Charsets.UTF_8;
import static java.util.Collections.unmodifiableSet;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileNodeStoreBuilder.DEFAULT_NUMBER_OF_SPLIT_STORE_SIZE;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileNodeStoreBuilder.OAK_INDEXER_USE_ZIP;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileNodeStoreBuilder.PROP_SPLIT_STORE_SIZE;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileStoreUtils.createReader;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileStoreUtils.createWriter;
import static org.apache.jackrabbit.oak.index.indexer.document.flatfile.FlatFileStoreUtils.getSortedStoreFileName;

public class FlatFileStoreSplitter {
    private static final Logger log = LoggerFactory.getLogger(FlatFileStoreSplitter.class);
    private final File workDir;
    private final boolean useZip = Boolean.parseBoolean(System.getProperty(OAK_INDEXER_USE_ZIP, "true"));
    private final int splitSize = Integer.getInteger(PROP_SPLIT_STORE_SIZE, DEFAULT_NUMBER_OF_SPLIT_STORE_SIZE);
    private final IndexerSupport indexerSupport;
    private final IndexHelper indexHelper;
    private final Charset charset = UTF_8;
    private final NodeTypeInfoProvider infoProvider;
    private final FlatFileStore ffs;
    private final NodeStore store;
    public final String splitDirName = "split";
    private final NodeStateEntryReader entryReader;
    private final Joiner pathJoiner = Joiner.on('/');
    private long minimumSplitThreshold = 10 * FileUtils.ONE_MB;
    private Set<IndexDefinition> indexDefinitions;

    public FlatFileStoreSplitter(FlatFileStore ffs, IndexHelper indexHelper, IndexerSupport indexerSupport) {
        this.ffs = ffs;
        this.indexerSupport = indexerSupport;
        this.indexHelper = indexHelper;
        this.workDir = new File(indexHelper.getWorkDir(), splitDirName);

        this.store = new MemoryNodeStore(indexerSupport.retrieveNodeStateForCheckpoint());
        this.infoProvider = new NodeStateNodeTypeInfoProvider(store.getRoot());
        this.entryReader = new NodeStateEntryReader(indexHelper.getGCBlobStore());
    }

    public FlatFileStoreSplitter(FlatFileStore ffs, IndexHelper indexHelper, IndexerSupport indexerSupport, long minimumSplitThreshold) {
        this(ffs, indexHelper, indexerSupport);
        this.minimumSplitThreshold = minimumSplitThreshold;
    }

    public List<FlatFileStore> split() throws IOException, CommitFailedException {
        List<File> splitFlatFiles = new ArrayList<>();
        List<FlatFileStore> splitFlatFileStores = new ArrayList<>();
        try {
            FileUtils.forceMkdir(workDir);
        } catch (IOException e) {
            log.error("failed to create split directory {}", workDir.getAbsolutePath());
            splitFlatFileStores.add(ffs);
            return splitFlatFileStores;
        }

        File originalFlatFile = new File(ffs.getFlatFileStorePath());
        long fileSizeInBytes = useZip ? getGzipUncompressedSizeInBytes(ffs.getFlatFileStorePath()) : originalFlatFile.length();
        log.info("original flatfile size: {}",  FileUtils.byteCountToDisplaySize(fileSizeInBytes));
        long splitThreshold = Math.round((double) (fileSizeInBytes / splitSize));
        log.info("split threshold: {} bytes, split size: {}",  FileUtils.byteCountToDisplaySize(splitThreshold), splitSize);


        // return original if file too small or split size equals 1
        if (splitThreshold < minimumSplitThreshold || splitSize <= 1) {
            log.info("split is not necessary, skip splitting");
            splitFlatFileStores.add(ffs);
            return splitFlatFileStores;
        }

        Set<String>splitNodeTypesName = getSplitNodeTypeNames();
        log.info("split allowed types: {}", splitNodeTypesName);

        try (BufferedReader reader = createReader(originalFlatFile, useZip)) {
            long readPos = 0;
            int outFileIndex = 1;
            File currentFile = new File(workDir, "split-" + outFileIndex + "-" + getSortedStoreFileName(useZip));
            BufferedWriter writer = createWriter(currentFile, useZip);
            splitFlatFiles.add(currentFile);

            String line;
            int lineCount = 0;
            Stack<String> nodeTypeNameStack = new Stack<>();
            while ((line = reader.readLine()) != null) {
                updateParentNodeTypes(nodeTypeNameStack, line);
                boolean shouldSplit = (readPos > splitThreshold) && (outFileIndex < splitSize);
                if (shouldSplit && canSplit(splitNodeTypesName, nodeTypeNameStack)) {
                    writer.close();
                    log.info("created split flat file {} with size {}", currentFile.getAbsolutePath(), FileUtils.byteCountToDisplaySize(currentFile.length()));
                    readPos = 0;
                    outFileIndex++;
                    currentFile = new File(workDir, "split-" + outFileIndex + "-" + getSortedStoreFileName(useZip));
                    writer = createWriter(currentFile, useZip);
                    splitFlatFiles.add(currentFile);
                    log.info("split position found at line {}, creating new split file {}", lineCount, currentFile.getAbsolutePath());
                }
                writer.append(line);
                writer.newLine();
                readPos += line.length() + 1;
                lineCount++;
            }
            writer.close();

            log.info("split total line count: {}", lineCount);
        }

        Set<String> preferredPathElements = getPreferredPathElements(getIndexDefinitions());

        for (File flatFile : splitFlatFiles) {
            splitFlatFileStores.add(new FlatFileStore(indexHelper.getGCBlobStore(), flatFile, new NodeStateEntryReader(indexHelper.getGCBlobStore()), unmodifiableSet(preferredPathElements), useZip));
        }
        return splitFlatFileStores;
    }

    private void updateParentNodeTypes(Stack<String> parentNodeTypeNames, String line) {
        NodeStateHolder ns = new SimpleNodeStateHolder(line);
        List<String> pathElements = ns.getPathElements();
        int currentLineDepth = pathElements.size();
        int parentTypesDepth = parentNodeTypeNames.size();
        if (currentLineDepth > parentTypesDepth) {
            parentNodeTypeNames.add(getJCRPrimaryType(line));
        } else {
            int popSize = parentTypesDepth - currentLineDepth + 1;
            if (parentTypesDepth > 0) {
                for (int i = 0; i < popSize; i++) {
                    parentNodeTypeNames.pop();
                }
            }
            parentNodeTypeNames.add(getJCRPrimaryType(line));
        }
//        log.info("===x size {}={}", currentLineDepth, parentNodeTypeNames.size());
//        log.info("line {}", line);
//        log.info("parentNodeTypeNames {}", parentNodeTypeNames);
    }

    private String getJCRPrimaryType(String line) {
        NodeStateEntry nse = entryReader.read(line);
        PropertyState property = nse.getNodeState().getProperty(JCR_PRIMARYTYPE);
        if (property == null) {
            return "";
        }
        Type<?> type = property.getType();
        if (type == Type.NAME) {
            return property.getValue(Type.NAME);
        }
        return "";
    }

    private boolean canSplit(Set<String> nodeTypes, Stack<String> nodeTypeNameStack) {
        for (String parentNodeTypeName : nodeTypeNameStack.subList(0, nodeTypeNameStack.size()-1)) {
            if (nodeTypes.contains(parentNodeTypeName)) {
                return false;
            }
        }
        return true;
    }

    // Source http://www.abeel.be/content/determine-uncompressed-size-gzip-file
    private int getGzipUncompressedSizeInBytes(String filepath) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(filepath, "r");
        raf.seek(raf.length() - 4);
        int b4 = raf.read();
        int b3 = raf.read();
        int b2 = raf.read();
        int b1 = raf.read();
        int val = (b1 << 24) | (b2 << 16) + (b3 << 8) + b4;
        raf.close();

        return val;
    }

    private Set<NodeTypeInfo> getSubTypes(String nodeTypeName) {
        Set<NodeTypeInfo> initialSet = new HashSet<>();
        NodeTypeInfo nodeType = infoProvider.getNodeTypeInfo(nodeTypeName);

        Set<String> subTypes = new HashSet<>(nodeType.getMixinSubTypes());
        subTypes.addAll(nodeType.getPrimarySubTypes());
//        System.out.println("- " + nodeTypeName);
//        System.out.println("  - PrimarySubTypes" + nodeType.getPrimarySubTypes());
//        System.out.println("  - MixinSubTypes" + nodeType.getMixinSubTypes());

        for (String subTypeName: subTypes) {
            initialSet.add(infoProvider.getNodeTypeInfo(subTypeName));
            initialSet.addAll(getSubTypes(subTypeName));
        }

        return initialSet;
    }

    private Set<String> getSplitNodeTypeNames() throws IOException, CommitFailedException {
        Set<IndexDefinition> indexDefinitions = getIndexDefinitions();
        Set<NodeTypeInfo> splitNodeTypes = getSplitNodeType(indexDefinitions);
        return splitNodeTypes.stream().map(NodeTypeInfo::getNodeTypeName).collect(Collectors.toSet());
    }

    private Set<IndexDefinition> getIndexDefinitions() throws IOException, CommitFailedException {
        if (indexDefinitions != null) {
            return indexDefinitions;
        }

        NodeState root = store.getRoot();
        NodeBuilder builder = root.builder();

        indexerSupport.updateIndexDefinitions(builder);
        IndexDefinition.Builder indexDefBuilder = new IndexDefinition.Builder();

        indexDefinitions = new HashSet<>();
        for (String indexPath : indexHelper.getIndexPaths()) {
            NodeBuilder idxBuilder = IndexerSupport.childBuilder(builder, indexPath, false);
            IndexDefinition indexDf = indexDefBuilder.defn(idxBuilder.getNodeState()).indexPath(indexPath).root(root).build();
            indexDefinitions.add(indexDf);
        }

        return indexDefinitions;
    }

    private Set<NodeTypeInfo> getSplitNodeType(Set<IndexDefinition> indexDefinitions) throws IOException, CommitFailedException {
        HashSet<String> nodeTypeNameSet = new HashSet<>();
        Set<NodeTypeInfo> setOfNodeType = new HashSet<>();

        for (IndexDefinition indexDf : indexDefinitions) {
            Map<String, Aggregate> aggregateMap = indexDf.getAggregates();
            nodeTypeNameSet.addAll(Objects.requireNonNull(aggregateMap).keySet());
            nodeTypeNameSet.addAll(indexDf.getDefinedRules().stream().map(IndexDefinition.IndexingRule::getBaseNodeType).collect(Collectors.toList()));
        }

        for (String nodeTypeName : nodeTypeNameSet) {
            setOfNodeType.add(infoProvider.getNodeTypeInfo(nodeTypeName));
            setOfNodeType.addAll(getSubTypes(nodeTypeName));
        }

        log.debug("split node types: {}", setOfNodeType);
        return setOfNodeType;
    }

    private Set<String> getPreferredPathElements(Set<IndexDefinition> indexDefinitions) {
        Set<String> preferredPathElements = new HashSet<>();

        for (IndexDefinition indexDf : indexDefinitions) {
            preferredPathElements.addAll(indexDf.getRelativeNodeNames());
        }
        return preferredPathElements;
    }
}
