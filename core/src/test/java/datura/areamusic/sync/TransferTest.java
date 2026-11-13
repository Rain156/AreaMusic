package datura.areamusic.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferTest {
    @TempDir
    Path tempDir;

    @Test
    void sendsAndReceivesAFileInChunks() throws Exception {
        byte[] content = randomBytes(100 * 1024);
        Path source = write("source.ogg", content);
        Path temporary = tempDir.resolve("cache/.incoming.part");
        Path destination = tempDir.resolve("cache/final.ogg");
        int chunks = 0;

        try (OutgoingTransfer outgoing = new OutgoingTransfer(source, TransferLimits.UPLOAD_CHUNK_BYTES);
             IncomingTransfer incoming = new IncomingTransfer(temporary, content.length, Sha256.of(source))) {
            assertEquals(content.length, outgoing.size());
            while (!outgoing.finished()) {
                OutgoingTransfer.Chunk chunk = outgoing.next();
                assertTrue(chunk.data().length <= TransferLimits.UPLOAD_CHUNK_BYTES);
                assertFalse(incoming.complete());
                incoming.accept(chunk.offset(), chunk.data(), chunk.last());
                chunks++;
            }
            assertEquals(4, chunks);
            assertEquals(content.length, outgoing.offset());
            assertTrue(incoming.complete());
            assertEquals(content.length, incoming.received());
            assertEquals(destination, incoming.moveTo(destination));
            assertThrows(IllegalStateException.class, outgoing::next);
        }

        assertArrayEquals(content, Files.readAllBytes(destination));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void emptyFileIsOneEmptyLastChunk() throws Exception {
        Path source = write("empty.ogg", new byte[0]);

        try (OutgoingTransfer outgoing = new OutgoingTransfer(source, 16);
             IncomingTransfer incoming = new IncomingTransfer(
                     tempDir.resolve(".empty.part"), 0, Sha256.of(source))) {
            assertFalse(outgoing.finished());
            OutgoingTransfer.Chunk chunk = outgoing.next();
            assertEquals(0, chunk.data().length);
            assertTrue(chunk.last());
            assertTrue(outgoing.finished());
            incoming.accept(chunk.offset(), chunk.data(), chunk.last());
            assertTrue(incoming.complete());
        }
    }

    @Test
    void senderFailsWhenTheFileShrinksMidTransfer() throws Exception {
        Path source = write("source.ogg", randomBytes(1000));

        try (OutgoingTransfer outgoing = new OutgoingTransfer(source, 400)) {
            outgoing.next();
            Files.write(source, new byte[500], StandardOpenOption.TRUNCATE_EXISTING);

            assertThrows(IOException.class, () -> {
                outgoing.next();
                outgoing.next();
            });
        }
    }

    @Test
    void receiverRejectsOutOfOrderAndRepeatedChunks() throws Exception {
        byte[] content = randomBytes(300);
        String sha256 = sha256(content);

        IncomingTransfer skipped = new IncomingTransfer(tempDir.resolve(".a.part"), 300, sha256);
        assertReason(TransferException.Reason.OUT_OF_ORDER,
                () -> skipped.accept(100, Arrays.copyOfRange(content, 100, 200), false));

        IncomingTransfer repeated = new IncomingTransfer(tempDir.resolve(".b.part"), 300, sha256);
        repeated.accept(0, Arrays.copyOfRange(content, 0, 100), false);
        assertReason(TransferException.Reason.OUT_OF_ORDER,
                () -> repeated.accept(0, Arrays.copyOfRange(content, 0, 100), false));
        assertReason(TransferException.Reason.CLOSED,
                () -> repeated.accept(100, Arrays.copyOfRange(content, 100, 200), false));

        assertNoFilesLeft();
    }

    @Test
    void receiverRejectsWrongSizeAndWrongContent() throws Exception {
        byte[] content = randomBytes(300);
        String sha256 = sha256(content);

        IncomingTransfer tooLarge = new IncomingTransfer(tempDir.resolve(".a.part"), 300, sha256);
        assertReason(TransferException.Reason.TOO_LARGE, () -> tooLarge.accept(0, new byte[301], false));

        IncomingTransfer tooSmall = new IncomingTransfer(tempDir.resolve(".b.part"), 300, sha256);
        assertReason(TransferException.Reason.SIZE_MISMATCH,
                () -> tooSmall.accept(0, Arrays.copyOfRange(content, 0, 299), true));

        byte[] tampered = content.clone();
        tampered[150] ^= 1;
        IncomingTransfer wrongHash = new IncomingTransfer(tempDir.resolve(".c.part"), 300, sha256);
        assertReason(TransferException.Reason.HASH_MISMATCH, () -> wrongHash.accept(0, tampered, true));
        assertFalse(wrongHash.complete());
        assertThrows(IllegalStateException.class, () -> wrongHash.moveTo(tempDir.resolve("out.ogg")));

        assertNoFilesLeft();
    }

    @Test
    void closingAnUnfinishedTransferDeletesItsTemporaryFile() throws Exception {
        Path temporary = tempDir.resolve(".a.part");
        IncomingTransfer incoming = new IncomingTransfer(temporary, 300, sha256(new byte[300]));
        incoming.accept(0, new byte[100], false);
        assertTrue(Files.exists(temporary));

        incoming.close();
        incoming.close();

        assertFalse(Files.exists(temporary));
        assertReason(TransferException.Reason.CLOSED, () -> incoming.accept(100, new byte[100], false));
    }

    @Test
    void moveToReplacesAnExistingFileAndCanOnlyHappenOnce() throws Exception {
        byte[] content = randomBytes(64);
        Path destination = write("existing.ogg", new byte[]{1, 2, 3});
        IncomingTransfer incoming = new IncomingTransfer(tempDir.resolve(".a.part"), 64, sha256(content));

        assertThrows(IllegalStateException.class, () -> incoming.moveTo(destination));
        incoming.accept(0, content, true);
        incoming.moveTo(destination);
        incoming.close();

        assertArrayEquals(content, Files.readAllBytes(destination));
        assertThrows(IllegalStateException.class, () -> incoming.moveTo(destination));
    }

    @Test
    void temporaryPathMustNotAlreadyExist() throws Exception {
        Path temporary = write(".taken.part", new byte[1]);

        assertThrows(IOException.class, () -> new IncomingTransfer(temporary, 1, sha256(new byte[1])));
        assertTrue(Files.exists(temporary));
    }

    private void assertReason(TransferException.Reason expected, ThrowingAction action) {
        TransferException error = assertThrows(TransferException.class, action::run);
        assertEquals(expected, error.reason());
    }

    private void assertNoFilesLeft() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    private Path write(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    private static String sha256(byte[] content) {
        return Sha256.hex(Sha256.newDigest().digest(content));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
