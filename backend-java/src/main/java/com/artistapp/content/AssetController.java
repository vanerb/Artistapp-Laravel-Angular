package com.artistapp.content;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    private final Path publicRoot;
    public AssetController(@Value("${artistapp.media.public-root:../backend/storage/app/public}") String root) {
        this.publicRoot=Paths.get(root).toAbsolutePath().normalize();
    }
    @GetMapping("/{*path}")
    public ResponseEntity<Resource> get(@PathVariable String path) {
        String relative=path==null?"":path.replaceFirst("^/","");
        Path target=publicRoot.resolve(relative).normalize();
        if(!target.startsWith(publicRoot)||!Files.isRegularFile(target))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            Resource resource=new UrlResource(target.toUri());
            String type=Files.probeContentType(target);
            MediaType media=type==null?MediaType.APPLICATION_OCTET_STREAM:MediaType.parseMediaType(type);
            if(!(media.getType().equals("image")||media.equals(MediaType.APPLICATION_OCTET_STREAM)))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Solo se permiten recursos públicos.");
            return ResponseEntity.ok().contentType(media).header("X-Content-Type-Options","nosniff").body(resource);
        } catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
    }
}