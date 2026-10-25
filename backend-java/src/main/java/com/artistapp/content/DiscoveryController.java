package com.artistapp.content;

import com.artistapp.security.JwtService;
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
public class DiscoveryController {
    private final JdbcTemplate jdbc;
    public DiscoveryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/users")
    public Map<String,Object> users(@RequestParam(required=false) String q,
                                   @RequestParam(required=false) Integer tipo,
                                   @RequestParam(defaultValue="1") int page) {
        int size=24, offset=(Math.max(1,page)-1)*size;
        String filter=" WHERE 1=1 ";
        List<Object> args=new ArrayList<>();
        if(q!=null&&!q.isBlank()){filter+=" AND (usuario LIKE ? OR nombre LIKE ?) ";args.add("%"+q+"%");args.add("%"+q+"%");}
        if(tipo!=null){filter+=" AND id_tipo=? ";args.add(tipo);}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM usuarios "+filter,Long.class,args.toArray());
        args.add(size);args.add(offset);
        List<Map<String,Object>> data=jdbc.queryForList("SELECT id,nombre,apellidos,usuario,id_tipo,imagen,biografia FROM usuarios "+filter+" ORDER BY usuario LIMIT ? OFFSET ?",args.toArray());
        return page(data,total==null?0:total,page,size);
    }

    @GetMapping("/users/{id}")
    public Map<String,Object> user(@PathVariable long id) {
        Map<String,Object> user=one("SELECT id,nombre,apellidos,usuario,id_tipo,imagen,biografia FROM usuarios WHERE id=?",id);
        user.put("publicaciones",jdbc.queryForList("SELECT * FROM publicaciones WHERE id_usuario=? AND tipo='public' ORDER BY id DESC LIMIT 6",id));
        user.put("avisos",jdbc.queryForList("SELECT * FROM avisos WHERE id_usuario=? ORDER BY id DESC LIMIT 6",id));
        user.put("eventos",jdbc.queryForList("SELECT * FROM eventos WHERE id_usuario=? ORDER BY fecha_inicio DESC LIMIT 6",id));
        user.put("seguidores_count",jdbc.queryForObject("SELECT COUNT(DISTINCT id_usuario) FROM favoritos WHERE id_usuariopin=?",Long.class,id));
        user.put("seguidos_count",jdbc.queryForObject("SELECT COUNT(DISTINCT id_usuariopin) FROM favoritos WHERE id_usuario=?",Long.class,id));
        return user;
    }

    @GetMapping("/users/{id}/community")
    public Map<String,Object> community(@PathVariable long id,@RequestParam String tipo,@RequestParam(defaultValue="1") int page) {
        if(!Set.of("seguidores","seguidos").contains(tipo)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de comunidad no válido.");
        int size=30,offset=(Math.max(1,page)-1)*size;
        String where=tipo.equals("seguidores")?"f.id_usuariopin=?":"f.id_usuario=?";
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM favoritos f WHERE "+where,Long.class,id);
        List<Map<String,Object>> data=jdbc.queryForList("SELECT DISTINCT u.id,u.usuario,u.imagen,u.biografia FROM favoritos f JOIN usuarios u ON u.id="+(tipo.equals("seguidores")?"f.id_usuario":"f.id_usuariopin")+" WHERE "+where+" ORDER BY u.usuario LIMIT ? OFFSET ?",id,size,offset);
        return page(data,total==null?0:total,page,size);
    }

    @GetMapping("/publications")
    public Map<String,Object> publications(@RequestParam(required=false) String q,@RequestParam(required=false) Integer categoria,@RequestParam(defaultValue="1") int page) {
        int size=24,offset=(Math.max(1,page)-1)*size;
        String joins=" FROM publicaciones p LEFT JOIN usuarios u ON u.id=p.id_usuario LEFT JOIN categoria c ON c.id=p.id_categoria ";
        String filter=" WHERE p.tipo='public' "; List<Object> args=new ArrayList<>();
        if(q!=null&&!q.isBlank()){filter+=" AND (p.titulo LIKE ? OR p.descripcion LIKE ?) ";args.add("%"+q+"%");args.add("%"+q+"%");}
        if(categoria!=null){filter+=" AND p.id_categoria=? ";args.add(categoria);}
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+joins+filter,Long.class,args.toArray());
        args.add(size);args.add(offset);
        List<Map<String,Object>> data=jdbc.queryForList("SELECT p.*,u.usuario AS artist_name,u.imagen AS artist_image,c.nombre AS category_name"+joins+filter+" ORDER BY p.megusta DESC,p.id DESC LIMIT ? OFFSET ?",args.toArray());
        return page(data,total==null?0:total,page,size);
    }

    @GetMapping("/categories")
    public List<Map<String,Object>> categories(){return jdbc.queryForList("SELECT id,nombre FROM categoria ORDER BY nombre");}
    @GetMapping("/product-types")
    public List<Map<String,Object>> productTypes(){return jdbc.queryForList("SELECT id,nombre FROM tipo_producto ORDER BY id");}

    @GetMapping("/products")
    public Map<String,Object> products(@RequestParam(required=false) String q,@RequestParam(required=false) Integer tipo,@RequestParam(required=false) Boolean disponible,@RequestParam(defaultValue="1") int page) {
        int size=24,offset=(Math.max(1,page)-1)*size;
        String joins=" FROM productos p JOIN usuarios u ON u.id=p.id_usuario LEFT JOIN tipo_producto t ON t.id=p.id_tipo ";
        String filter=" WHERE 1=1 ";List<Object> args=new ArrayList<>();
        if(q!=null&&!q.isBlank()){filter+=" AND (p.nombre LIKE ? OR p.descripcion LIKE ? OR u.usuario LIKE ?) ";args.add("%"+q+"%");args.add("%"+q+"%");args.add("%"+q+"%");}
        if(tipo!=null){filter+=" AND p.id_tipo=? ";args.add(tipo);}
        if(Boolean.TRUE.equals(disponible))filter+=" AND p.cantidad>0 ";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+joins+filter,Long.class,args.toArray());
        args.add(size);args.add(offset);
        List<Map<String,Object>> data=jdbc.queryForList("SELECT p.id,p.nombre,p.descripcion,p.precio,p.cantidad,p.imagen,p.id_tipo,p.id_usuario,u.usuario AS artist_name,u.imagen AS artist_image,t.nombre AS type_name"+joins+filter+" ORDER BY p.id DESC LIMIT ? OFFSET ?",args.toArray());
        return page(data,total==null?0:total,page,size);
    }

    @GetMapping("/products/{id}")
    public Map<String,Object> product(@PathVariable long id){
        return one("SELECT p.id,p.nombre,p.descripcion,p.precio,p.cantidad,p.imagen,p.id_tipo,p.id_usuario,u.usuario AS artist_name,u.imagen AS artist_image,t.nombre AS type_name FROM productos p JOIN usuarios u ON u.id=p.id_usuario LEFT JOIN tipo_producto t ON t.id=p.id_tipo WHERE p.id=?",id);
    }

    @GetMapping("/notices")
    public List<Map<String,Object>> notices(){return jdbc.queryForList("SELECT a.*,u.usuario FROM avisos a LEFT JOIN usuarios u ON u.id=a.id_usuario ORDER BY a.fecha_publicacion DESC,a.hora DESC");}

    @GetMapping("/publications/{publicationId}/comments")
    public List<Map<String,Object>> comments(@PathVariable long publicationId){
        return jdbc.queryForList("SELECT c.id,c.publication_id,c.user_id,c.body,c.created_at,u.usuario,u.imagen FROM publication_comments c JOIN usuarios u ON u.id=c.user_id WHERE c.publication_id=? ORDER BY c.id ASC",publicationId);
    }

    private Map<String,Object> one(String sql,Object...args){
        List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No encontrado.");
        return new LinkedHashMap<>(rows.get(0));
    }
    private Map<String,Object> page(List<?> data,long total,int current,int size){
        Map<String,Object> out=new LinkedHashMap<>();int last=Math.max(1,(int)Math.ceil((double)total/size));
        out.put("current_page",current);out.put("data",data);out.put("per_page",size);out.put("total",total);out.put("last_page",last);
        out.put("from",data.isEmpty()?null:(current-1)*size+1);out.put("to",data.isEmpty()?null:(current-1)*size+data.size());
        out.put("first_page_url",null);out.put("last_page_url",null);out.put("next_page_url",current<last?"?page="+(current+1):null);out.put("prev_page_url",current>1?"?page="+(current-1):null);
        return out;
    }
}