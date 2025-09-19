package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.application.DeleteFileService;
import ae.teletronics.storage.application.ListFilesService;
import ae.teletronics.storage.application.RenameFileService;
import ae.teletronics.storage.application.UploadFileService;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = { FilesController.class, DownloadLinkBuilder.class })
class FilesControllerSliceUnitTest {

    @Autowired MockMvc mvc;

    @MockBean UploadFileService upload;
    @MockBean RenameFileService rename;
    @MockBean ListFilesService list;
    @MockBean DeleteFileService delete;

    @Test
    void getPublic_ok() throws Exception {
        FileEntry fe = new FileEntry();
        fe.setId("ID1");
        fe.setFilename("a.txt");
        fe.setVisibility(Visibility.PUBLIC);

        Page<FileEntry> page = new PageImpl<>(List.of(fe), PageRequest.of(0, 20), 1);

        when(list.listPublic(
                nullable(String.class),  // tag may be null
                anyInt(),
                anyInt(),
                nullable(String.class),  // sortBy may be null
                anyBoolean()
        )).thenReturn(page);

        mvc.perform(get("/files/public").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is("ID1")))
                .andExpect(jsonPath("$.content[0].filename", is("a.txt")));

        // (optional) prove the defaults were used
        verify(list).listPublic(null, 0, 20, null, false);
    }
}
