package com.artistapp.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class StoreController {
    private final JdbcTemplate jdbc;
    public StoreController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record ProductBody(@Size(max=255) String nombre,@Size(max=255) String descripcion,
                              @DecimalMin("0.01") BigDecimal precio,@Min(0) Integer cantidad,
                              @Min(1) @Max(2) Integer id_tipo,String imagen,String archivos) {}
    public record CheckoutBody(@NotBlank @Size(max=255) String nombre,
                               @NotBlank @Size(max=255) String direccion,
                               @NotBlank @Size(max=14) String telefono) {}

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createProduct(@Valid @RequestBody ProductBody b,Authentication auth){
        long uid=uid(auth);if(!Set.of(1,3,5).contains(role(uid)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        validateProduct(b,true);
        jdbc.update("INSERT INTO productos (nombre,descripcion,precio,cantidad,imagen,id_tipo,id_usuario,archivos) VALUES (?,?,?,?,?,?,?,?)",
            b.nombre().trim(),b.descripcion().trim(),b.precio(),b.cantidad(),safe(b.imagen()),b.id_tipo(),uid,b.id_tipo()==2?safe(b.archivos()):"Sin archivos");
        return one("SELECT id,nombre,descripcion,precio,cantidad,imagen,id_tipo,id_usuario FROM productos WHERE id=LAST_INSERT_ID()");
    }
    @PatchMapping("/products/{id}")
    public Map<String,Object> updateProduct(@PathVariable long id,@Valid @RequestBody ProductBody b,Authentication auth){
        long uid=uid(auth);Map<String,Object> p=one("SELECT * FROM productos WHERE id=?",id);
        if(((Number)p.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Map<String,Object> u=new LinkedHashMap<>();put(u,"nombre",b.nombre());put(u,"descripcion",b.descripcion());put(u,"precio",b.precio());put(u,"cantidad",b.cantidad());put(u,"id_tipo",b.id_tipo());
        if(b.imagen()!=null)u.put("imagen",b.imagen());
        if(b.archivos()!=null&&Objects.equals(b.id_tipo(),2))u.put("archivos",b.archivos());
        if(!u.isEmpty()){List<Object>a=new ArrayList<>(u.values());a.add(id);jdbc.update("UPDATE productos SET "+String.join(",",u.keySet().stream().map(k->k+"=?").toList())+" WHERE id=?",a.toArray());}
        return one("SELECT id,nombre,descripcion,precio,cantidad,imagen,id_tipo,id_usuario FROM productos WHERE id=?",id);
    }
    @DeleteMapping("/products/{id}")
    public Map<String,String> deleteProduct(@PathVariable long id,Authentication auth){
        long uid=uid(auth);Map<String,Object> p=one("SELECT * FROM productos WHERE id=?",id);
        if(((Number)p.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(exists("SELECT COUNT(*) FROM detalles_pedido WHERE id_producto=?",id))throw new ResponseStatusException(HttpStatus.CONFLICT,"No se puede eliminar un producto con pedidos asociados.");
        jdbc.update("DELETE FROM cesta_producto WHERE id_producto=?",id);jdbc.update("DELETE FROM productos WHERE id=?",id);
        return Map.of("message","Producto eliminado.");
    }

    @GetMapping("/cart")
    public Map<String,Object> cart(Authentication auth){
        long uid=uid(auth);Long cartId=findCart(uid);
        List<Map<String,Object>> items=cartId==null?List.of():jdbc.queryForList("SELECT p.id AS product_id,p.nombre,p.descripcion,p.precio,p.cantidad,p.imagen,p.id_tipo,p.id_usuario FROM cesta_producto cp JOIN productos p ON p.id=cp.id_producto WHERE cp.id_cesta=? ORDER BY cp.id",cartId);
        BigDecimal total=items.stream().map(x->new BigDecimal(x.get("precio").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
        return Map.of("id",cartId==null?0:cartId,"items",items,"total",total);
    }
    @PostMapping("/cart/items/{product}")
    public Map<String,Object> addCart(@PathVariable long product,Authentication auth){
        long uid=uid(auth);Map<String,Object> p=one("SELECT id,cantidad,id_usuario FROM productos WHERE id=?",product);
        if(((Number)p.get("cantidad")).intValue()<1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Producto agotado.");
        if(((Number)p.get("id_usuario")).longValue()==uid)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes comprar tu propio producto.");
        Long cid=getOrCreateCart(uid);
        if(!exists("SELECT COUNT(*) FROM cesta_producto WHERE id_cesta=? AND id_producto=?",cid,product))
            jdbc.update("INSERT INTO cesta_producto (id_cesta,id_producto) VALUES (?,?)",cid,product);
        return cart(auth);
    }
    @DeleteMapping("/cart/items/{product}")
    public Map<String,Object> removeCart(@PathVariable long product,Authentication auth){
        Long cid=findCart(uid(auth));if(cid!=null)jdbc.update("DELETE FROM cesta_producto WHERE id_cesta=? AND id_producto=?",cid,product);
        return cart(auth);
    }
    @DeleteMapping("/cart")
    public Map<String,String> clearCart(Authentication auth){
        Long cid=findCart(uid(auth));if(cid!=null)jdbc.update("DELETE FROM cesta_producto WHERE id_cesta=?",cid);
        return Map.of("message","Cesta vaciada.");
    }

    @PostMapping("/checkout")
    @Transactional
    public Map<String,Object> checkout(@Valid @RequestBody CheckoutBody b,Authentication auth){
        long uid=uid(auth);Long cid=findCart(uid);
        if(cid==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cesta está vacía.");
        List<Map<String,Object>> items=jdbc.queryForList("SELECT p.* FROM cesta_producto cp JOIN productos p ON p.id=cp.id_producto WHERE cp.id_cesta=? FOR UPDATE",cid);
        if(items.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cesta está vacía.");
        BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> p:items){
            if(((Number)p.get("cantidad")).intValue()<1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Un producto se ha agotado.");
            if(((Number)p.get("id_usuario")).longValue()==uid)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes comprar tus propios productos.");
            total=total.add(new BigDecimal(p.get("precio").toString()));
        }
        jdbc.update("INSERT INTO pedidos (id_usuario,nombre,estado,direccion,telefono,pago) VALUES (?,?,?,?,?,?)",uid,b.nombre().trim(),"pendiente",b.direccion().trim(),b.telefono().trim(),total.floatValue());
        Long orderId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        for(Map<String,Object> p:items){
            long pid=((Number)p.get("id")).longValue();
            int changed=jdbc.update("UPDATE productos SET cantidad=cantidad-1 WHERE id=? AND cantidad>0",pid);
            if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Stock insuficiente.");
            jdbc.update("INSERT INTO detalles_pedido (id_pedido,id_producto) VALUES (?,?)",orderId,pid);
        }
        jdbc.update("DELETE FROM cesta_producto WHERE id_cesta=?",cid);
        return one("SELECT * FROM pedidos WHERE id=?",orderId);
    }
    @GetMapping("/orders")
    public List<Map<String,Object>> orders(Authentication auth){
        long uid=uid(auth);
        List<Map<String,Object>> list=jdbc.queryForList("SELECT * FROM pedidos WHERE id_usuario=? ORDER BY id DESC",uid);
        for(Map<String,Object> o:list)o.put("productos",jdbc.queryForList("SELECT p.id,p.nombre,p.descripcion,p.precio,p.imagen,p.id_tipo FROM detalles_pedido d JOIN productos p ON p.id=d.id_producto WHERE d.id_pedido=?",o.get("id")));
        return list;
    }
    @GetMapping("/orders/received")
    public List<Map<String,Object>> received(Authentication auth){
        return jdbc.queryForList("SELECT DISTINCT o.* FROM pedidos o JOIN detalles_pedido d ON d.id_pedido=o.id JOIN productos p ON p.id=d.id_producto WHERE p.id_usuario=? ORDER BY o.id DESC",uid(auth));
    }
    @PatchMapping("/orders/{id}/status")
    public Map<String,Object> status(@PathVariable long id,@RequestBody Map<String,String> body,Authentication auth){
        String state=body.get("estado");
        if(!Set.of("pendiente","preparando","enviado","entregado","cancelado").contains(state))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Estado no válido.");
        long uid=uid(auth);
        if(!exists("SELECT COUNT(*) FROM detalles_pedido d JOIN productos p ON p.id=d.id_producto WHERE d.id_pedido=? AND p.id_usuario=?",id,uid)&&role(uid)!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE pedidos SET estado=? WHERE id=?",state,id);
        return one("SELECT * FROM pedidos WHERE id=?",id);
    }

    @PostMapping("/orders/{id}/cancel")
    @Transactional
    public Map<String,Object> cancelOrder(@PathVariable long id,Authentication auth){
        long uid=uid(auth);Map<String,Object> order=one("SELECT * FROM pedidos WHERE id=?",id);
        if(((Number)order.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String state=String.valueOf(order.get("estado"));
        if(Set.of("cancelado","cancelled","reembolsado").contains(state.toLowerCase()))return order;
        if(Set.of("enviado","entregado").contains(state.toLowerCase()))throw new ResponseStatusException(HttpStatus.CONFLICT,"No se puede cancelar un pedido enviado o entregado desde este endpoint.");
        List<Map<String,Object>> items=jdbc.queryForList("SELECT id_producto FROM detalles_pedido WHERE id_pedido=?",id);
        for(Map<String,Object> item:items)jdbc.update("UPDATE productos SET cantidad=cantidad+1 WHERE id=?",item.get("id_producto"));
        jdbc.update("UPDATE pedidos SET estado='cancelado' WHERE id=?",id);
        return one("SELECT * FROM pedidos WHERE id=?",id);
    }

    @PatchMapping("/orders/{id}/items/{detail}/confirm-received")
    @Transactional
    public Map<String,Object> confirmReceived(@PathVariable long id,@PathVariable long detail,Authentication auth){
        long uid=uid(auth);
        Map<String,Object> order=one("SELECT * FROM pedidos WHERE id=?",id);
        if(((Number)order.get("id_usuario")).longValue()!=uid)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!exists("SELECT COUNT(*) FROM detalles_pedido WHERE id=? AND id_pedido=?",detail,id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE pedidos SET estado='entregado' WHERE id=?",id);
        return one("SELECT * FROM pedidos WHERE id=?",id);
    }

    @DeleteMapping("/orders/{id}")
    public Map<String,String> deleteOrder(@PathVariable long id,Authentication auth){
        long uid=uid(auth);Map<String,Object> order=one("SELECT * FROM pedidos WHERE id=?",id);
        if(((Number)order.get("id_usuario")).longValue()!=uid&&role(uid)!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!"cancelado".equalsIgnoreCase(String.valueOf(order.get("estado"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"Solo se pueden eliminar pedidos cancelados.");
        jdbc.update("DELETE FROM detalles_pedido WHERE id_pedido=?",id);
        jdbc.update("DELETE FROM pedidos WHERE id=?",id);
        return Map.of("message","Pedido eliminado.");
    }

    private void validateProduct(ProductBody b,boolean required){
        if(required&&(b.nombre()==null||b.nombre().isBlank()||b.descripcion()==null||b.descripcion().isBlank()||b.precio()==null||b.cantidad()==null||b.id_tipo()==null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Completa todos los campos del producto.");
        if(b.id_tipo()!=null&&!Set.of(1,2).contains(b.id_tipo()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de producto no válido.");
        if(b.id_tipo()!=null&&b.id_tipo()==2&&(b.archivos()==null||b.archivos().isBlank()||b.archivos().equals("Sin archivos")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Los productos digitales necesitan un archivo.");
    }
    private String safe(String v){return v==null?"":v;}
    private void put(Map<String,Object> m,String k,Object v){if(v!=null)m.put(k,v);}
    private long uid(Authentication a){return ((Number)a.getPrincipal()).longValue();}
    private int role(long id){Integer n=jdbc.queryForObject("SELECT id_tipo FROM usuarios WHERE id=?",Integer.class,id);return n==null?0:n;}
    private boolean exists(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,args);return n!=null&&n>0;}
    private Long findCart(long uid){List<Long> r=jdbc.query("SELECT id FROM cesta WHERE id_usuario=? ORDER BY id LIMIT 1",(rs,n)->rs.getLong(1),uid);return r.isEmpty()?null:r.get(0);}
    private Long getOrCreateCart(long uid){Long id=findCart(uid);if(id!=null)return id;jdbc.update("INSERT INTO cesta (id_usuario) VALUES (?)",uid);return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No encontrado.");return new LinkedHashMap<>(r.get(0));}
}