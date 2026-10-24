package hnist.communication.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 瓦片内存缓存（LRU，按字节数上限淘汰）。
 * 用 JDK 自带 LinkedHashMap 实现，不引入额外依赖。
 */
public class TileCache {

    private final long maxBytes;
    private long totalBytes = 0;
    private final LinkedHashMap<String, byte[]> map;

    public TileCache(long maxBytes) {
        this.maxBytes = maxBytes;
        // accessOrder=true：get 也会调整访问顺序，因此读方法同样必须 synchronized。
        this.map = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return totalBytes > maxBytes;
            }
        };
    }

    public synchronized byte[] get(String key) {
        return map.get(key);
    }

    public synchronized void put(String key, byte[] data) {
        if (data == null || data.length > maxBytes) {
            return; // 单块超过总上限时直接不缓存，否则放入后会立刻被淘汰。
        }
        byte[] old = map.put(key, data);
        if (old != null) {
            totalBytes -= old.length;
        }
        totalBytes += data.length;
        // removeEldestEntry 在 put 内部触发时 totalBytes 还没计入新瓦片，这里再按字节上限兜底淘汰最旧项。
        while (totalBytes > maxBytes && !map.isEmpty()) {
            Map.Entry<String, byte[]> e = map.entrySet().iterator().next();
            map.remove(e.getKey());
            totalBytes -= e.getValue().length;
        }
    }

    public synchronized void clear() {
        map.clear();
        totalBytes = 0;
    }

    public synchronized long sizeBytes() {
        return totalBytes;
    }
}
