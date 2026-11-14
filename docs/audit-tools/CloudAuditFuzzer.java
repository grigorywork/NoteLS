import com.notessync.app.data.backup.BackupArchive;
import com.notessync.app.data.backup.crypto.*;
import com.notessync.app.notes.text.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Bounded synthetic corpus only. This tests a frozen cloud JVM artifact, never phone performance. */
public final class CloudAuditFuzzer {
    static final Function0<Unit> CHECK = () -> Unit.INSTANCE;
    static final char[] PASSWORD = "PUBLIC SYNTHETIC FUZZ FIXTURE ONLY".toCharArray();
    static final int CHUNK = 65536;
    static long seed, iterations, cryptoChecks, zipChecks, formatChecks;
    static Random random;
    static BackupIdentity identity;
    static BackupAccess access;
    static byte[] original = new byte[0], mutated = new byte[0];
    static Path root;
    static String variant = "startup";

    static byte[] encrypt(byte[] plain, BackupIdentity recipient) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupEncryptedIO.INSTANCE.write(output, recipient, 1000L, CHECK, stream -> {
            try { stream.write(plain); } catch (IOException e) { throw new UncheckedIOException(e); }
            return Unit.INSTANCE;
        });
        return output.toByteArray();
    }
    static byte[] decrypt(byte[] wire, BackupAccess lease) {
        // This property concerns the explicitly encrypted format. Automatic legacy detection is
        // intentionally permissive until its caller validates the ZIP, so do not use a raw-byte
        // passthrough action as a substitute for the application restore pipeline.
        ByteArrayInputStream input = new ByteArrayInputStream(wire);
        BackupEnvelope envelope = BackupEnvelopeCodec.INSTANCE.read(input);
        try (EncryptedBackupStreams.Reader reader = new EncryptedBackupStreams.Reader(input,envelope,lease,CHECK)) {
            return reader.readAllBytes();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void rejects(byte[] wire) {
        boolean rejected = false;
        try { decrypt(wire, access); } catch (Exception expected) { rejected = true; }
        require(rejected, "Invalid encrypted framing accepted"); cryptoChecks++;
    }
    static void crypto() {
        int[] boundaries = {0,1,CHUNK-1,CHUNK,CHUNK+1,2*CHUNK,2*CHUNK+1,4*CHUNK+17};
        int size = random.nextBoolean() ? boundaries[random.nextInt(boundaries.length)] : random.nextInt(4*CHUNK+18);
        byte[] plain = new byte[size]; random.nextBytes(plain);
        original = encrypt(plain, identity); variant = "valid-roundtrip";
        require(Arrays.equals(plain, decrypt(original, access)), "Roundtrip changed bytes"); cryptoChecks++;
        variant = "single-bit"; mutated = original.clone();
        int position = random.nextInt(mutated.length); mutated[position] ^= (byte)(1 << random.nextInt(8)); rejects(mutated);
        variant = "truncation"; mutated = Arrays.copyOf(original, random.nextInt(original.length)); rejects(mutated);
        variant = "trailing-data"; mutated = Arrays.copyOf(original, original.length + 1 + random.nextInt(32));
        for (int i=original.length;i<mutated.length;i++) mutated[i]=(byte)random.nextInt(256); rejects(mutated);
        if (iterations % 1000 == 0) {
            variant = "key-rotation";
            BackupIdentity next = identity.changePassword(access, PASSWORD);
            require(!next.getId().equals(identity.getId()), "Rotation retained old recipient");
            byte[] future = encrypt(new byte[] {42,43,44}, next);
            try (BackupAccess newAccess = next.unlock(PASSWORD)) {
                require(Arrays.equals(new byte[] {42,43,44}, decrypt(future,newAccess)), "New key failed"); cryptoChecks++;
            }
            rejects(future);
        }
    }
    static byte[] zip(String path, byte[] payload) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("backup.json"));
            zip.write(BackupArchive.INSTANCE.manifest(new BackupArchive.Info(1000L,0L,17)).getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("notessync.db")); zip.write("SQLite format 3\0fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(path)); zip.write(payload); zip.closeEntry();
        }
        return raw.toByteArray();
    }
    static void erase(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var entries=Files.walk(directory)) {
            for (Path entry: entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
    static void archives() throws IOException {
        int type = random.nextInt(5);
        String path;
        int count = random.nextInt(2049);
        boolean valid;
        if(type==0) { path="attachments/picture-"+iterations; valid=true; }
        else if(type==1) { path="attachments/directory-"+iterations+"/"; valid=(count==0); }
        else if(type==2) { path="attachments/../outside"; valid=false; }
        else if(type==3) { path="attachments/"+"deep/".repeat(33)+"file"; valid=false; }
        else { path="attachments/"+"n".repeat(4097); valid=false; }
        byte[] payload=new byte[count]; random.nextBytes(payload);
        long budget = random.nextBoolean() ? 8192L : 128L;
        byte[] bytes=zip(path,payload);
        int manifestSize=BackupArchive.INSTANCE.manifest(new BackupArchive.Info(1000L,0L,17)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        valid = valid && count + manifestSize + "SQLite format 3\0fixture".length() <= budget;
        Path directory=root.resolve("extracted"); erase(directory);
        boolean accepted;
        variant="zip-type-"+type+"-budget-"+budget;
        try { BackupArchive.INSTANCE.extract(new ByteArrayInputStream(bytes),directory.toFile(),budget,CHECK); accepted=true; }
        catch (Exception rejected) { accepted=false; }
        require(valid==accepted,"ZIP budget/path acceptance differs from contract");
        require(!Files.exists(root.resolve("outside")),"ZIP wrote outside staging");
        erase(directory); zipChecks++;
    }
    static void pipeline() throws IOException {
        byte[] plain = zip("attachments/small",new byte[] {1,2,3});
        byte[] wire = encrypt(plain,identity);
        Path dir = root.resolve("pipeline"); erase(dir);
        variant="pipeline-valid";
        BackupEncryptedIO.INSTANCE.read(new ByteArrayInputStream(wire),access,CHECK,
            stream -> BackupArchive.INSTANCE.extract(stream,dir.toFile(),8192L,CHECK));
        erase(dir); cryptoChecks++;
        variant="pipeline-damaged-magic";
        byte[] damaged=wire.clone(); damaged[random.nextInt(8)]^=1;
        boolean rejected=false;
        try { BackupEncryptedIO.INSTANCE.read(new ByteArrayInputStream(damaged),access,CHECK,
            stream -> BackupArchive.INSTANCE.extract(stream,dir.toFile(),8192L,CHECK)); }
        catch(Exception expected) { rejected=true; }
        require(rejected,"Damaged encrypted magic bypassed ZIP validation");erase(dir);cryptoChecks++;
        variant="pipeline-missing-final-tag";
        byte[] truncated=Arrays.copyOf(wire,wire.length-1); rejected=false;
        try { BackupEncryptedIO.INSTANCE.read(new ByteArrayInputStream(truncated),access,CHECK,
            stream -> BackupArchive.INSTANCE.extract(stream,dir.toFile(),8192L,CHECK)); }
        catch(Exception expected) { rejected=true; }
        require(rejected,"ZIP EOF bypassed final encrypted tag");erase(dir);cryptoChecks++;
    }

    static void formats() {
        String full="ab😀".repeat(50+random.nextInt(950));
        int start=random.nextInt(full.length()); int end=start+1+random.nextInt(full.length()-start);
        FormatMark mark=new FormatMark(start,end,"BOLD","1");
        TextFormatting formatting=new TextFormatting(1,List.of(mark)); String raw=formatting.encode();
        int prefix=1+random.nextInt(Math.min(full.length(),2000));
        if(prefix<full.length() && Character.isHighSurrogate(full.charAt(prefix-1)) && Character.isLowSurrogate(full.charAt(prefix))) prefix--;
        String visible=full.substring(0,prefix); variant="format-prefix";
        TextFormatting clipped=TextFormatting.Companion.decodePreview(raw,visible);
        for(FormatMark range:clipped.getMarks()) {
            require(range.getStart()>=0 && range.getStart()<range.getEnd() && range.getEnd()<=visible.length(),"Preview range escaped visible text");
            require(!(range.getStart()>0 && Character.isHighSurrogate(visible.charAt(range.getStart()-1)) && Character.isLowSurrogate(visible.charAt(range.getStart()))),"Start split emoji");
            require(!(range.getEnd()<visible.length() && Character.isHighSurrogate(visible.charAt(range.getEnd()-1)) && Character.isLowSurrogate(visible.charAt(range.getEnd()))),"End split emoji");
        }
        if(start<visible.length()) require(!clipped.getMarks().isEmpty(),"Overlapping valid range disappeared");
        require(TextFormatting.Companion.decode(raw,full.length()).getMarks().get(0).getEnd()==end,"Preview changed full document source");
        if(end>visible.length()) require(TextFormatting.Companion.decode(raw,visible.length()).getMarks().isEmpty(),"Strict document decode became permissive");
        formatChecks++;
    }
    static void report(String status,long active,long excluded) {
        System.out.printf(Locale.ROOT,"{\"status\":\"%s\",\"utc\":\"%s\",\"seed\":%d,\"iterations\":%d,\"crypto_checks\":%d,\"zip_checks\":%d,\"format_checks\":%d,\"active_seconds\":%.3f,\"excluded_seconds\":%.3f}%n",status,Instant.now(),seed,iterations,cryptoChecks,zipChecks,formatChecks,active/1e9,excluded/1e9);
        System.out.flush();
    }
    public static void main(String[] args) throws Exception {
        long seconds=Long.parseLong(args[0]);seed=Long.parseLong(args[1]);random=new Random(seed);
        root=Path.of(args[2]);Files.createDirectories(root);
        identity=BackupIdentity.Companion.create(PASSWORD,100000); access=identity.unlock(PASSWORD);
        long active=0,excluded=0,last=System.nanoTime(),printed=last;
        report("started",active,excluded);
        try {
            do {
                crypto(); archives(); formats(); if(iterations%40==0) pipeline(); iterations++;
                long now=System.nanoTime(),delta=now-last; last=now;
                if(delta>30_000_000_000L) { excluded+=delta; report("execution_gap_excluded",active,excluded); }
                else active+=delta;
                if(now-printed>=15_000_000_000L) { report("running",active,excluded);printed=now; }
            } while(active<seconds*1_000_000_000L && !Files.exists(root.resolve("STOP")));
            report(Files.exists(root.resolve("STOP"))?"stopped":"completed",active,excluded);
        } catch(Throwable failure) {
            Files.write(root.resolve("original-fixture.bin"),original); Files.write(root.resolve("mutated-fixture.bin"),mutated);
            report("FAILED-"+variant,active,excluded);failure.printStackTrace();System.exit(1);
        } finally {access.close();Arrays.fill(PASSWORD,'\0');}
    }
}
