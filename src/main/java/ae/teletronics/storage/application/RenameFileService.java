package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.exceptions.ConflictException;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.application.exceptions.ForbiddenOperationException;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.FileEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RenameFileService {

    private final FileEntryRepository files;

    public RenameFileService(FileEntryRepository files) { this.files = files; }

    public void rename(String ownerId, String fileId, String newFilename) {
        FileEntry entry = files.findById(fileId)
                .orElseThrow(() -> new NotFoundException("File not found"));
        if (!entry.getOwnerId().equals(ownerId)) {
            throw new ForbiddenOperationException("You are not the owner of this file");
        }

        String newLc = newFilename.toLowerCase(Locale.ROOT);
        if (files.existsByOwnerIdAndFilenameLc(ownerId, newLc)) {
            throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, "Filename already exists");
        }

        entry.setFilename(newFilename);
        try {
            files.save(entry);
        }  catch (OptimisticLockingFailureException e) {
            throw new ConflictException("File was modified concurrently, please retry", e);
        } catch (DuplicateKeyException dke) {
            throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, "Filename already exists");
        }
    }
}
