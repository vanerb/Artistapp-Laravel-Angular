package com.artistapp.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CommunityAdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    public CommunityAdminController(JdbcTemplate jdbc,PasswordEncoder passwordEncoder){this.jdbc=jdbc;this.passwordEncoder=passwordEncoder;}
    public record NoticeBody(@Size(max=255) String titulo,@Size(max=255) String aviso,String fecha_publicacion,String hora) {}
    public record CategoryBody(@NotBlank @Size(max=255) String nombre) {}
    public record ReportBody(@NotBlank @Size(max=255) String informe,Long id_usuarioreferenciado,String estado) {}

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(Authentication auth){return jdbc.queryForList("SELECT * FROM app_notifications WHERE user_id=? ORDER BY id DESC LIMIT 100",uid(auth));}
    @PatchMapping("/notifications/read-all")
    public Map<String,Object> readAll(Authentication auth){jdbc.update("UPDATE app_notifications SET read_at=NOW(),updated_at=NOW() WHERE user_id=? AND read_at IS NULL",uid(auth));return Map.of("message","Notificaciones marcadas como leídas.");}
    @PatchMapping("/notifications/{id}/read")
    public Map<String,Object> readOne(@PathVariable long id,Authentication auth){
        int n=jdbc.update("UPDATE app_notifications SET read_at=NOW(),updated_at=NOW() WHERE id=? AND user_id=?",id,uid(auth));
        if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return one("SELECT * FROM app_notifications WHERE id=?",id);
    }

    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createNotice(@Valid @RequestBody NoticeBody b,Authentication auth){
        long uid=uid(auth);if(!Set.of(1,3).contains(role(uid)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(b.titulo()==null||b.titulo().isBlank()||b.aviso()==null||b.aviso().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Título y aviso son obligatorios.");
        jdbc.update("INSERT INTO avisos (titulo,aviso,fecha_publicacion,id_usuario,hora) VALUES (?,?,COALESCE(?,CURDATE()),?,COALESCE(?,CURTIME()))",b.titulo().trim(),b.aviso().trim(),b.fecha_publicacion(),uid,b.hora());
        return one("SELECT * FROM avisos WHERE id=LAST_INSERT_ID()");
    }
    @PatchMapping("/notices/{id}")
    public Map<String,Object> updateNotice(@PathVariable long id,@Valid @RequestBody NoticeBody b,Authentication auth){
        long uid=uid(auth);Map<String,Object> old=one("SELECT * FROM avisos WHERE id=?",id);
        if(((Number)old.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE avisos SET titulo=?,aviso=?,fecha_publicacion=?,hora=? WHERE id=?",b.titulo()==null?old.get("titulo"):b.titulo(),b.aviso()==null?old.get("aviso"):b.aviso(),b.fecha_publicacion()==null?old.get("fecha_publicacion"):b.fecha_publicacion(),b.hora()==null?old.get("hora"):b.hora(),id);
        return one("SELECT * FROM avisos WHERE id=?",id);
    }
    @DeleteMapping("/notices/{id}")
    public Map<String,String> deleteNotice(@PathVariable long id,Authentication auth){
        long uid=uid(auth);Map<String,Object> old=one("SELECT * FROM avisos WHERE id=?",id);
        if(((Number)old.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.update("DELETE FROM avisos WHERE id=?",id);return Map.of("message","Aviso eliminado.");
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createCategory(@Valid @RequestBody CategoryBody b,Authentication auth){
        requireAdmin(uid(auth));jdbc.update("INSERT INTO categoria (nombre) VALUES (?)",b.nombre().trim());return one("SELECT * FROM categoria WHERE id=LAST_INSERT_ID()");
    }
    @PatchMapping("/categories/{id}")
    public Map<String,Object> updateCategory(@PathVariable long id,@Valid @RequestBody CategoryBody b,Authentication auth){
        requireAdmin(uid(auth));if(jdbc.update("UPDATE categoria SET nombre=? WHERE id=?",b.nombre().trim(),id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return one("SELECT * FROM categoria WHERE id=?",id);
    }
    @DeleteMapping("/categories/{id}")
    public Map<String,String> deleteCategory(@PathVariable long id,Authentication auth){
        requireAdmin(uid(auth));if(exists("SELECT COUNT(*) FROM publicaciones WHERE id_categoria=?",id))throw new ResponseStatusException(HttpStatus.CONFLICT,"La categoría contiene publicaciones.");
        if(jdbc.update("DELETE FROM categoria WHERE id=?",id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return Map.of("message","Categoría eliminada.");
    }

    @GetMapping("/reports")
    public List<Map<String,Object>> reports(Authentication auth){long uid=uid(auth);if(role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);return jdbc.queryForList("SELECT i.*,u.usuario AS reporter_username,r.usuario AS reported_username FROM informes i LEFT JOIN usuarios u ON u.id=i.id_usuario LEFT JOIN usuarios r ON r.id=i.id_usuarioreferenciado ORDER BY i.id DESC");}
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createReport(@Valid @RequestBody ReportBody b,Authentication auth){
        long uid=uid(auth);if(b.id_usuarioreferenciado()==null||!exists("SELECT COUNT(*) FROM usuarios WHERE id=?",b.id_usuarioreferenciado()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Indica un usuario válido.");
        jdbc.update("INSERT INTO informes (id_usuario,informe,id_usuarioreferenciado,estado) VALUES (?,?,?,'pendiente')",uid,b.informe().trim(),b.id_usuarioreferenciado());
        return one("SELECT * FROM informes WHERE id=LAST_INSERT_ID()");
    }
    @PatchMapping("/reports/{id}")
    public Map<String,Object> updateReport(@PathVariable long id,@RequestBody ReportBody b,Authentication auth){
        requireAdmin(uid(auth));if(b.estado()==null||!Set.of("pendiente","resuelta","rechazada").contains(b.estado()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Estado no válido.");
        if(jdbc.update("UPDATE informes SET estado=? WHERE id=?",b.estado(),id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return one("SELECT * FROM informes WHERE id=?",id);
    }
    @DeleteMapping("/reports/{id}")
    public Map<String,String> deleteReport(@PathVariable long id,Authentication auth){
        requireAdmin(uid(auth));if(jdbc.update("DELETE FROM informes WHERE id=?",id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return Map.of("message","Informe eliminado.");
    }

    @GetMapping("/admin/summary")
    public Map<String,Object> adminSummary(Authentication auth){
        requireAdmin(uid(auth));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("users",jdbc.queryForObject("SELECT COUNT(*) FROM usuarios",Long.class));
        out.put("publications",jdbc.queryForObject("SELECT COUNT(*) FROM publicaciones",Long.class));
        out.put("events",jdbc.queryForObject("SELECT COUNT(*) FROM eventos",Long.class));
        out.put("products",jdbc.queryForObject("SELECT COUNT(*) FROM productos",Long.class));
        out.put("orders",jdbc.queryForObject("SELECT COUNT(*) FROM pedidos",Long.class));
        out.put("reports_pending",jdbc.queryForObject("SELECT COUNT(*) FROM informes WHERE estado='pendiente'",Long.class));
        return out;
    }
    @GetMapping("/admin/users")
    public List<Map<String,Object>> adminUsers(Authentication auth){requireAdmin(uid(auth));return jdbc.queryForList("SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios ORDER BY id DESC");}
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> adminCreateUser(@RequestBody Map<String,Object> b,Authentication auth){
        requireAdmin(uid(auth));
        String nombre=string(b,"nombre"),apellidos=string(b,"apellidos"),usuario=string(b,"usuario"),correo=string(b,"correo"),password=string(b,"password");
        int role;try{role=Integer.parseInt(string(b,"id_tipo"));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rol no válido.");}
        if(!Set.of(1,3,5,7).contains(role))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rol no válido.");
        if(password.length()<8)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Contraseña demasiado corta.");
        jdbc.update("INSERT INTO usuarios (nombre,apellidos,usuario,correo,contrasena,id_tipo,imagen,biografia) VALUES (?,?,?,?,?,?,?,?)",nombre,apellidos,usuario,correo,passwordEncoder.encode(password),role,"media/no-user.png","Aún no tiene");
        return one("SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios WHERE id=LAST_INSERT_ID()");
    }
    @PatchMapping("/admin/users/{id}")
    public Map<String,Object> adminUpdateUser(@PathVariable long id,@RequestBody Map<String,Object> b,Authentication auth){
        requireAdmin(uid(auth));Map<String,Object> old=one("SELECT * FROM usuarios WHERE id=?",id);Map<String,Object> u=new LinkedHashMap<>();
        for(String k:List.of("nombre","apellidos","usuario","correo","biografia","id_tipo"))if(b.containsKey(k)&&b.get(k)!=null)u.put(k,b.get(k));
        if(b.containsKey("password")&&b.get("password")!=null&&!b.get("password").toString().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El cambio de contraseña administrativa debe realizarse mediante un flujo seguro.");
        if(!u.isEmpty()){List<Object>a=new ArrayList<>(u.values());a.add(id);jdbc.update("UPDATE usuarios SET "+String.join(",",u.keySet().stream().map(k->k+"=?").toList())+" WHERE id=?",a.toArray());}
        return one("SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios WHERE id=?",id);
    }
    @DeleteMapping("/admin/users/{id}")
    public Map<String,String> adminDeleteUser(@PathVariable long id,Authentication auth){
        requireAdmin(uid(auth));if(id==uid(auth))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes eliminar tu propia cuenta administrativa.");
        if(exists("SELECT COUNT(*) FROM pedidos WHERE id_usuario=?",id))throw new ResponseStatusException(HttpStatus.CONFLICT,"El usuario tiene pedidos asociados y no puede eliminarse de forma segura.");
        jdbc.update("DELETE FROM favoritos WHERE id_usuario=? OR id_usuariopin=?",id,id);
        jdbc.update("DELETE FROM usuarios WHERE id=?",id);return Map.of("message","Usuario eliminado.");
    }

    private String string(Map<String,Object> b,String k){Object v=b.get(k);if(v==null||v.toString().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Falta el campo "+k);return v.toString().trim();}
    private void requireAdmin(long id){if(role(id)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    private long uid(Authentication a){return ((Number)a.getPrincipal()).longValue();}
    private int role(long id){Integer n=jdbc.queryForObject("SELECT id_tipo FROM usuarios WHERE id=?",Integer.class,id);return n==null?0:n;}
    private boolean exists(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,args);return n!=null&&n>0;}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No encontrado.");return new LinkedHashMap<>(r.get(0));}
}