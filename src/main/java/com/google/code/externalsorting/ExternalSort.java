package com.google.code.externalsorting;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ExternalSort {

    public static void main(final String[] args) throws IOException {
        if (args.length < 2) {
            displayUsage();
            return;
        }

        boolean verbose = false, distinct = false, useGzip = false;
        int maxtmpfiles = DEFAULTMAXTEMPFILES, headerSize = 0;
        Charset cs = Charset.defaultCharset();
        String inputfile = null, outputfile = null;
        File tempFileStore = null;

        for (int param = 0; param < args.length; param++) {
            switch (args[param]) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-d":
                case "--distinct":
                    distinct = true;
                    break;
                case "-t":
                case "--maxtmpfiles":
                    maxtmpfiles = Integer.parseInt(args[++param]);
                    break;
                case "-c":
                case "--charset":
                    cs = Charset.forName(args[++param]);
                    break;
                case "-z":
                case "--gzip":
                    useGzip = true;
                    break;
                case "-H":
                case "--header":
                    headerSize = Integer.parseInt(args[++param]);
                    break;
                case "-s":
                case "--store":
                    tempFileStore = new File(args[++param]);
                    break;
                default:
                    if (inputfile == null) {
                        inputfile = args[param];
                    } else if (outputfile == null) {
                        outputfile = args[param];
                    } else {
                        System.out.println("Unparsed: " + args[param]);
                    }
            }
        }

        if (outputfile == null) {
            System.out.println("Please provide input and output file names");
            displayUsage();
            return;
        }

        if (inputfile == null) {
            System.out.println("No input file provided");
            displayUsage();
            return;
        }

        Comparator<String> comparator = Comparator.naturalOrder();
        List<File> tempFiles = sortInBatch(new File(inputfile), comparator,
                maxtmpfiles, cs, tempFileStore, distinct, headerSize, useGzip);

        if (verbose) {
            System.out.println("Created " + tempFiles.size() + " temp files");
        }

        mergeSortedFiles(tempFiles, new File(outputfile), comparator, cs,
                distinct, false, useGzip);
    }

    private static void displayUsage() {
        System.out.println("Usage: java com.google.code.externalsorting.ExternalSort inputfile outputfile");
        System.out.println("Flags are:");
        System.out.println("-v or --verbose: verbose output");
        System.out.println("-d or --distinct: prune duplicate lines");
        System.out.println("-t or --maxtmpfiles (followed by an integer): specify an upper bound on the number of temporary files");
        System.out.println("-c or --charset (followed by a charset code): specify the character set to use (for sorting)");
        System.out.println("-z or --gzip: use compression for the temporary files");
        System.out.println("-H or --header (followed by an integer): ignore the first few lines");
        System.out.println("-s or --store (followed by a path): where to store the temporary files");
        System.out.println("-h or --help: display this message");
    }

    public static long mergeSortedFiles(List<File> files, File outputFile,
                                        Comparator<String> cmp, Charset cs, boolean distinct,
                                        boolean append, boolean useGzip) throws IOException {
        ArrayList<IOStringStack> bfbs = new ArrayList<>();
        for (File f : files) {
            final int BUFFER_SIZE = 2048;
            try (InputStream in = new FileInputStream(f);
                 BufferedReader br = useGzip ? new BufferedReader(new InputStreamReader(
                                 new GZIPInputStream(in, BUFFER_SIZE), cs))
                         : new BufferedReader(new InputStreamReader(in, cs))) {
                bfbs.add(new IOStringStack(br));
            }
        }

        try (BufferedWriter fbw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile, append), cs))) {
            return mergeSortedFiles(fbw, cmp, distinct, bfbs);
        } finally {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static long mergeSortedFiles(BufferedWriter fbw, Comparator<String> cmp,
                                         boolean distinct, List<IOStringStack> buffers) throws IOException {
        PriorityQueue<IOStringStack> pq = new PriorityQueue<>(Comparator.comparing(IOStringStack::peek, cmp));
        pq.addAll(buffers);

        long rowCounter = 0;
        String lastLine = null;

        while (!pq.isEmpty()) {
            IOStringStack bfb = pq.poll();
            String line = bfb.pop();

            if (!distinct || (lastLine == null || cmp.compare(lastLine, line) != 0)) {
                fbw.write(line);
                fbw.newLine();
                lastLine = line;
                rowCounter++;
            }

            if (!bfb.empty()) {
                pq.add(bfb);
            }
        }

        return rowCounter;
    }

    public static List<File> sortInBatch(File file, Comparator<String> cmp,
                                         int maxtmpfiles, Charset cs, File tmpdirectory,
                                         boolean distinct, int numHeader, boolean useGzip) throws IOException {
        long blockSize = estimateBestSizeOfBlocks(file.length(), maxtmpfiles, Runtime.getRuntime().maxMemory());

        List<File> tempFiles = new ArrayList<>();
        try (BufferedReader fbr = new BufferedReader(new InputStreamReader(new FileInputStream(file), cs))) {
            List<String> block = new ArrayList<>();
            String line;
            long currentBlockSize = 0;

            while ((line = fbr.readLine()) != null) {
                if (currentBlockSize + line.length() + 40 > blockSize) {
                    tempFiles.add(saveBlockToFile(block, cmp, cs, tmpdirectory, distinct, useGzip));
                    block.clear();
                    currentBlockSize = 0;
                }

                block.add(line);
                currentBlockSize += line.length() + 40;
            }

            if (!block.isEmpty()) {
                tempFiles.add(saveBlockToFile(block, cmp, cs, tmpdirectory, distinct, useGzip));
            }
        }
        return tempFiles;
    }

    private static File saveBlockToFile(List<String> block,
                                        Comparator<String> cmp, Charset cs, File tempDir,
                                        boolean distinct, boolean useGzip) throws IOException {

        Collections.sort(block, cmp);

        File tempFile = File.createTempFile("sortInBatch", ".tmp", tempDir);
        tempFile.deleteOnExit();

        try (OutputStream os = useGzip ? new GZIPOutputStream(new FileOutputStream(tempFile)) : new FileOutputStream(tempFile);
             BufferedWriter fbw = new BufferedWriter(new OutputStreamWriter(os, cs))) {

            String lastLine = null;
            for (String line : block) {
                if (!distinct || (lastLine == null || cmp.compare(lastLine, line) != 0)) {
                    fbw.write(line);
                    fbw.newLine();
                    lastLine = line;
                }
            }
        }
        return tempFile;
    }

    public static long estimateBestSizeOfBlocks(final long sizeOfFile,
                                                final int maxTmpFiles, final long maxMemory) {
        long blockSize = sizeOfFile / maxTmpFiles + (sizeOfFile % maxTmpFiles == 0 ? 0 : 1);

        if (blockSize < maxMemory / 2) {
            blockSize = maxMemory / 2;
        }

        return blockSize;
    }

    public static final int DEFAULTMAXTEMPFILES = 1000;
}
