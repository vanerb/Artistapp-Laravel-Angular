package com.artistapp.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public AccountController(JdbcTemplate jdbc, PasswordEncoder encoder){this.jdbc=jdbc;this.encoder=encoder;}

    public record AccountUpdate(
        @Size(max=100) String nombre,
        @Size(max=140) String apellidos,
        @Size(max=100) String usuario,
        @Email @Size(max=240) String correo,
        @Size(max=255) String biografia,
        String password_actual,
        @Size(min=8,max=240) String password,
        String password_confirmation
    ) {}
    public record FavoriteBody(Boolean favorito) {}
    public record PublicationBody(@Size(max=255) String titulo,@Size(max=255) String descripcion,Integer id_categoria) {}
    public record CommentBody(@Size(min=1,max=1000) String body) {}

    @GetMapping("/account")
    public Map<String,Object> me(Authentication auth){
        long id=userId(auth);
        Map<String,Object> u=one("SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios WHERE id=?",id);
        u.put("publicaciones",jdbc.queryForList("SELECT * FROM publicaciones WHERE id_usuario=? ORDER BY id DESC",id));
        u.put("avisos",jdbc.queryForList("SELECT * FROM avisos WHERE id_usuario=? ORDER BY id DESC",id));
        u.put("eventos_propios",jdbc.queryForList("SELECT * FROM eventos WHERE id_usuario=? ORDER BY fecha_inicio DESC",id));
        u.put("productos",jdbc.queryForList("SELECT id,nombre,descripcion,precio,cantidad,imagen,id_tipo FROM productos WHERE id_usuario=? ORDER BY id DESC",id));
        u.put("favoritos",jdbc.queryForList("SELECT u.id,u.usuario,u.imagen,u.biografia FROM favoritos f JOIN usuarios u ON u.id=f.id_usuariopin WHERE f.id_usuario=? ORDER BY u.usuario",id));
        u.put("seguidores",jdbc.queryForList("SELECT u.id,u.usuario,u.imagen,u.biografia FROM favoritos f JOIN usuarios u ON u.id=f.id_usuario WHERE f.id_usuariopin=? ORDER BY u.usuario",id));
        u.put("eventos",jdbc.queryForList("SELECT e.*,le.estado AS participacion FROM listado_evento le JOIN eventos e ON e.id=le.id_evento WHERE le.id_usuario=? ORDER BY e.fecha_inicio DESC",id));
        return u;
    }

    @PatchMapping("/account")
    public Map<String,Object> update(@Valid @RequestBody AccountUpdate b,Authentication auth){
        long id=userId(auth);
        Map<String,Object> old=one("SELECT * FROM usuarios WHERE id=?",id);
        Map<String,Object> update=new LinkedHashMap<>();
        put(update,"nombre",b.nombre());put(update,"apellidos",b.apellidos());put(update,"usuario",b.usuario());put(update,"correo",b.correo()==null?null:b.correo().trim().toLowerCase());put(update,"biografia",b.biografia());
        for(String key:List.of("usuario","correo")){
            if(update.containsKey(key)){
                Long count=jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE "+key+"=? AND id<>?",Long.class,update.get(key),id);
                if(count!=null&&count>0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"El usuario o correo ya está en uso.");
            }
        }
        if(b.password()!=null&&!b.password().isBlank()){
            if(b.password_actual()==null||!encoder.matches(b.password_actual(),String.valueOf(old.get("contrasena"))))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"La contraseña actual no es correcta.");
            if(!b.password().equals(b.password_confirmation()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Las contraseñas no coinciden.");
            if(!b.password().matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$"))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"La contraseña debe contener mayúsculas, minúsculas y números.");
            update.put("contrasena",encoder.encode(b.password()));
        }
        if(!update.isEmpty()){
            String set=String.join(",",update.keySet().stream().map(k->k+"=?").toList());
            List<Object> args=new ArrayList<>(update.values());args.add(id);
            jdbc.update("UPDATE usuarios SET "+set+" WHERE id=?",args.toArray());
        }
        return one("SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios WHERE id=?",id);
    }

    @PostMapping("/users/{targetId}/favorite")
    public Map<String,Object> favorite(@PathVariable long targetId,Authentication auth){
        long viewer=userId(auth);
        if(viewer==targetId)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes seguirte a ti mismo.");
        if(!exists("SELECT COUNT(*) FROM usuarios WHERE id=?",targetId))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Usuario no encontrado.");
        boolean follows=exists("SELECT COUNT(*) FROM favoritos WHERE id_usuario=? AND id_usuariopin=?",viewer,targetId);
        if(follows)jdbc.update("DELETE FROM favoritos WHERE id_usuario=? AND id_usuariopin=?",viewer,targetId);
        else jdbc.update("INSERT INTO favoritos (id_usuario,id_usuariopin) VALUES (?,?)",viewer,targetId);
        return Map.of("fijado",!follows,"following",!follows);
    }

    @PostMapping("/publications")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createPublication(@Valid @RequestBody PublicationBody b,Authentication auth){
        long uid=userId(auth);int role=role(uid);
        if(!Set.of(1,3,5).contains(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No tienes permiso para publicar.");
        if(b.titulo()==null||b.titulo().isBlank()||b.descripcion()==null||b.descripcion().isBlank()||b.id_categoria()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Título, descripción y categoría son obligatorios.");
        if(!exists("SELECT COUNT(*) FROM categoria WHERE id=?",b.id_categoria()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Categoría no encontrada.");
        jdbc.update("INSERT INTO publicaciones (titulo,descripcion,tipo,id_categoria,id_usuario,megusta,imagen) VALUES (?,?, 'public',?,?,0,'')",b.titulo().trim(),b.descripcion().trim(),b.id_categoria(),uid);
        Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        return one("SELECT * FROM publicaciones WHERE id=?",id);
    }

    @PatchMapping("/publications/{id}")
    public Map<String,Object> updatePublication(@PathVariable long id,@Valid @RequestBody PublicationBody b,Authentication auth){
        long uid=userId(auth);Map<String,Object> post=one("SELECT * FROM publicaciones WHERE id=?",id);
        if(((Number)post.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Map<String,Object> u=new LinkedHashMap<>();put(u,"titulo",b.titulo());put(u,"descripcion",b.descripcion());put(u,"id_categoria",b.id_categoria());
        if(!u.isEmpty()){List<Object>a=new ArrayList<>(u.values());a.add(id);jdbc.update("UPDATE publicaciones SET "+String.join(",",u.keySet().stream().map(k->k+"=?").toList())+" WHERE id=?",a.toArray());}
        return one("SELECT * FROM publicaciones WHERE id=?",id);
    }

    @DeleteMapping("/publications/{id}")
    public Map<String,String> deletePublication(@PathVariable long id,Authentication auth){
        long uid=userId(auth);Map<String,Object> p=one("SELECT * FROM publicaciones WHERE id=?",id);
        if(((Number)p.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.update("DELETE FROM megusta WHERE id_publicacion=?",id);
        jdbc.update("DELETE FROM publication_comments WHERE publication_id=?",id);
        jdbc.update("DELETE FROM publicaciones WHERE id=?",id);
        return Map.of("message","Publicación eliminada.");
    }

    @PostMapping("/publications/{id}/like")
    public Map<String,Object> like(@PathVariable long id,Authentication auth){
        long uid=userId(auth);if(!exists("SELECT COUNT(*) FROM publicaciones WHERE id=?",id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean liked=exists("SELECT COUNT(*) FROM megusta WHERE id_usuario=? AND id_publicacion=? AND megusta=1",uid,id);
        if(liked)jdbc.update("UPDATE megusta SET megusta=0 WHERE id_usuario=? AND id_publicacion=?",uid,id);
        else {
            if(exists("SELECT COUNT(*) FROM megusta WHERE id_usuario=? AND id_publicacion=?",uid,id))
                jdbc.update("UPDATE megusta SET megusta=1 WHERE id_usuario=? AND id_publicacion=?",uid,id);
            else jdbc.update("INSERT INTO megusta (id_usuario,id_publicacion,megusta) VALUES (?,?,1)",uid,id);
        }
        Long count=jdbc.queryForObject("SELECT COUNT(DISTINCT id_usuario) FROM megusta WHERE id_publicacion=? AND megusta=1",Long.class,id);
        jdbc.update("UPDATE publicaciones SET megusta=? WHERE id=?",count==null?0:count.intValue(),id);
        return Map.of("liked",!liked,"count",count==null?0:count);
    }

    @PostMapping("/publications/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addComment(@PathVariable long id,@Valid @RequestBody CommentBody b,Authentication auth){
        if(!exists("SELECT COUNT(*) FROM publicaciones WHERE id=?",id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.update("INSERT INTO publication_comments (publication_id,user_id,body,created_at,updated_at) VALUES (?,?,?,NOW(),NOW())",id,userId(auth),b.body().trim());
        Long cid=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        return one("SELECT c.id,c.publication_id,c.user_id,c.body,c.created_at,u.usuario,u.imagen FROM publication_comments c JOIN usuarios u ON u.id=c.user_id WHERE c.id=?",cid);
    }

    @DeleteMapping("/publications/{publicationId}/comments/{commentId}")
    public Map<String,String> deleteComment(@PathVariable long publicationId,@PathVariable long commentId,Authentication auth){
        long uid=userId(auth);Map<String,Object> c=one("SELECT * FROM publication_comments WHERE id=? AND publication_id=?",commentId,publicationId);
        if(((Number)c.get("user_id")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.update("DELETE FROM publication_comments WHERE id=?",commentId);
        return Map.of("message","Comentario eliminado.");
    }

    @PatchMapping("/publications/{id}/visibility")
    public Map<String,Object> visibility(@PathVariable long id,Authentication auth){
        if(role(userId(auth))!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Map<String,Object> p=one("SELECT * FROM publicaciones WHERE id=?",id);
        String next="public".equals(p.get("tipo"))?"private":"public";
        jdbc.update("UPDATE publicaciones SET tipo=? WHERE id=?",next,id);
        return Map.of("tipo",next);
    }

    private long userId(Authentication a){return ((Number)a.getPrincipal()).longValue();}
    private int role(long id){Integer n=jdbc.queryForObject("SELECT id_tipo FROM usuarios WHERE id=?",Integer.class,id);return n==null?0:n;}
    private boolean exists(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,args);return n!=null&&n>0;}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No encontrado.");return new LinkedHashMap<>(r.get(0));}
    private void put(Map<String,Object> m,String k,Object v){if(v!=null)m.put(k,v);}
}