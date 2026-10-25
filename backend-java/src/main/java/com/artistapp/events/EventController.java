package com.artistapp.events;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class EventController {
    private final JdbcTemplate jdbc;
    public EventController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record EventBody(@Size(max=255) String nombre,@Size(max=255) String ubicacion,String link,String fecha_inicio,String fecha_fin) {}
    public record ParticipantDecision(@NotBlank String estado) {}
    public record MessageBody(@NotBlank @Size(max=1000) String body) {}

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody EventBody b,Authentication auth){
        long uid=uid(auth);if(!Set.of(1,3).contains(role(uid)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Solo organizadores pueden crear eventos.");
        validateEvent(b,true);
        jdbc.update("INSERT INTO eventos (nombre,ubicacion,link,fecha_inicio,fecha_fin,id_usuario,imagen) VALUES (?,?,?,?,?,?, '')",
            b.nombre().trim(),b.ubicacion().trim(),b.link()==null?"":b.link(),b.fecha_inicio(),b.fecha_fin(),uid);
        return one("SELECT * FROM eventos WHERE id=LAST_INSERT_ID()");
    }
    @PatchMapping("/events/{id}")
    public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody EventBody b,Authentication auth){
        long uid=uid(auth);Map<String,Object> e=one("SELECT * FROM eventos WHERE id=?",id);authorizeOwner(e,uid);
        String nombre=b.nombre()!=null?b.nombre():(String)e.get("nombre");
        String ubicacion=b.ubicacion()!=null?b.ubicacion():(String)e.get("ubicacion");
        String link=b.link()!=null?b.link():(String)e.get("link");
        String start=b.fecha_inicio()!=null?b.fecha_inicio():String.valueOf(e.get("fecha_inicio"));
        String end=b.fecha_fin()!=null?b.fecha_fin():String.valueOf(e.get("fecha_fin"));
        if(end.compareTo(start)<0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"La fecha final debe ser igual o posterior a la inicial.");
        jdbc.update("UPDATE eventos SET nombre=?,ubicacion=?,link=?,fecha_inicio=?,fecha_fin=? WHERE id=?",nombre,ubicacion,link,start,end,id);
        return one("SELECT * FROM eventos WHERE id=?",id);
    }
    @DeleteMapping("/events/{id}")
    public Map<String,String> delete(@PathVariable long id,Authentication auth){
        long uid=uid(auth);Map<String,Object> e=one("SELECT * FROM eventos WHERE id=?",id);authorizeOwner(e,uid);
        jdbc.update("DELETE FROM event_chat_messages WHERE event_id=?",id);
        jdbc.update("DELETE FROM listado_evento WHERE id_evento=?",id);
        jdbc.update("DELETE FROM eventos WHERE id=?",id);
        return Map.of("message","Evento eliminado.");
    }
    @GetMapping("/events/{id}/participants")
    public List<Map<String,Object>> participants(@PathVariable long id){
        if(!exists("SELECT COUNT(*) FROM eventos WHERE id=?",id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return jdbc.queryForList("SELECT le.id,le.estado,u.id AS user_id,u.usuario,u.imagen,u.biografia FROM listado_evento le JOIN usuarios u ON u.id=le.id_usuario WHERE le.id_evento=? ORDER BY u.usuario",id);
    }
    @PostMapping("/events/{id}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> join(@PathVariable long id,Authentication auth){
        long uid=uid(auth);if(!exists("SELECT COUNT(*) FROM eventos WHERE id=?",id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT id,estado FROM listado_evento WHERE id_evento=? AND id_usuario=?",id,uid);
        if(!old.isEmpty())return Map.of("message","Ya has solicitado participar.","estado",old.get(0).get("estado"));
        jdbc.update("INSERT INTO listado_evento (id_evento,id_usuario,estado) VALUES (?,?,'pendiente')",id,uid);
        return Map.of("message","Solicitud enviada.","estado","pendiente");
    }
    @DeleteMapping("/events/{id}/participants/me")
    public Map<String,String> leave(@PathVariable long id,Authentication auth){
        jdbc.update("DELETE FROM listado_evento WHERE id_evento=? AND id_usuario=?",id,uid(auth));
        return Map.of("message","Has salido del evento.");
    }
    @PatchMapping("/events/{id}/participants/{participant}")
    public Map<String,Object> decide(@PathVariable long id,@PathVariable long participant,@Valid @RequestBody ParticipantDecision body,Authentication auth){
        long uid=uid(auth);authorizeOwner(one("SELECT * FROM eventos WHERE id=?",id),uid);
        if(!Set.of("aceptado","rechazado","pendiente").contains(body.estado()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Estado no válido.");
        int n=jdbc.update("UPDATE listado_evento SET estado=? WHERE id=? AND id_evento=?",body.estado(),participant,id);
        if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("estado",body.estado());
    }
    @GetMapping("/events/{id}/chat")
    public List<Map<String,Object>> eventChat(@PathVariable long id,Authentication auth){
        long uid=uid(auth);
        if(!exists("SELECT COUNT(*) FROM listado_evento WHERE id_evento=? AND id_usuario=? AND estado='aceptado'",id,uid)
           && !exists("SELECT COUNT(*) FROM eventos WHERE id=? AND id_usuario=?",id,uid) && role(uid)!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Debes participar en el evento para acceder al chat.");
        return jdbc.queryForList("SELECT m.id,m.event_id,m.user_id,m.body,m.created_at,u.usuario,u.imagen FROM event_chat_messages m JOIN usuarios u ON u.id=m.user_id WHERE m.event_id=? ORDER BY m.id ASC",id);
    }
    @PostMapping("/events/{id}/chat")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> sendEventChat(@PathVariable long id,@Valid @RequestBody MessageBody b,Authentication auth){
        long uid=uid(auth);
        if(!exists("SELECT COUNT(*) FROM listado_evento WHERE id_evento=? AND id_usuario=? AND estado='aceptado'",id,uid)
           && !exists("SELECT COUNT(*) FROM eventos WHERE id=? AND id_usuario=?",id,uid) && role(uid)!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Debes participar en el evento para escribir en el chat.");
        jdbc.update("INSERT INTO event_chat_messages (event_id,user_id,body,created_at,updated_at) VALUES (?,?,?,NOW(),NOW())",id,uid,b.body().trim());
        return one("SELECT m.id,m.event_id,m.user_id,m.body,m.created_at,u.usuario,u.imagen FROM event_chat_messages m JOIN usuarios u ON u.id=m.user_id WHERE m.id=LAST_INSERT_ID()");
    }
    @GetMapping("/direct-messages/{otherId}")
    public List<Map<String,Object>> directMessages(@PathVariable long otherId,Authentication auth){
        long uid=uid(auth);
        if(!exists("SELECT COUNT(*) FROM usuarios WHERE id=?",otherId))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return jdbc.queryForList("SELECT d.id,d.sender_id,d.recipient_id,d.body,d.created_at,s.usuario AS sender_username,s.imagen AS sender_image,r.usuario AS recipient_username,r.imagen AS recipient_image FROM direct_messages d JOIN usuarios s ON s.id=d.sender_id JOIN usuarios r ON r.id=d.recipient_id WHERE (d.sender_id=? AND d.recipient_id=?) OR (d.sender_id=? AND d.recipient_id=?) ORDER BY d.id ASC",uid,otherId,otherId,uid);
    }
    @PostMapping("/direct-messages/{otherId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> sendDirectMessage(@PathVariable long otherId,@Valid @RequestBody MessageBody b,Authentication auth){
        long uid=uid(auth);if(uid==otherId)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes enviarte mensajes a ti mismo.");
        if(!exists("SELECT COUNT(*) FROM usuarios WHERE id=?",otherId))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.update("INSERT INTO direct_messages (sender_id,recipient_id,body,created_at,updated_at) VALUES (?,?,?,NOW(),NOW())",uid,otherId,b.body().trim());
        return one("SELECT d.id,d.sender_id,d.recipient_id,d.body,d.created_at,s.usuario AS sender_username,s.imagen AS sender_image,r.usuario AS recipient_username,r.imagen AS recipient_image FROM direct_messages d JOIN usuarios s ON s.id=d.sender_id JOIN usuarios r ON r.id=d.recipient_id WHERE d.id=LAST_INSERT_ID()");
    }

    private void validateEvent(EventBody b,boolean required){
        if(required&&(b.nombre()==null||b.nombre().isBlank()||b.ubicacion()==null||b.ubicacion().isBlank()||b.fecha_inicio()==null||b.fecha_fin()==null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nombre, ubicación y fechas son obligatorios.");
        if(b.fecha_inicio()!=null&&b.fecha_fin()!=null&&b.fecha_fin().compareTo(b.fecha_inicio())<0)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"La fecha final debe ser igual o posterior a la inicial.");
    }
    private void authorizeOwner(Map<String,Object> e,long uid){if(((Number)e.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    private long uid(Authentication a){return ((Number)a.getPrincipal()).longValue();}
    private int role(long id){Integer n=jdbc.queryForObject("SELECT id_tipo FROM usuarios WHERE id=?",Integer.class,id);return n==null?0:n;}
    private boolean exists(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,args);return n!=null&&n>0;}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No encontrado.");return new LinkedHashMap<>(r.get(0));}
}