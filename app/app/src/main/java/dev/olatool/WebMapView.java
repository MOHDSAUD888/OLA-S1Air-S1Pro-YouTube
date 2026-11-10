package dev.olatool;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * A real slippy map in a WebView, driven by Leaflet. A drop-in replacement for
 * the old canvas {@code MapView}: it exposes the same
 * {@link #setScooter}/{@link #setMe}/{@link #setTrail}/{@link #setLive} calls.
 *
 * <p>Base layers: OpenStreetMap streets, Esri satellite, Esri hybrid, plus
 * Google street/satellite/hybrid and a Google live-traffic overlay. The owner
 * switches them from the layer control. The marker moves with each new fix from
 * {@link #setScooter}, so the scooter's position updates live without the page
 * reloading or the owner tapping Refresh.
 *
 * <p>Read-only: it only shows a position the app already has.
 */
public final class WebMapView extends WebView {

    private boolean ready;
    /** JS calls that arrived before the page finished loading. Flushed on ready. */
    private final ArrayDeque<String> pending = new ArrayDeque<>();

    @SuppressLint("SetJavaScriptEnabled")
    public WebMapView(Context c) {
        super(c);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        getSettings().setBuiltInZoomControls(false);
        setBackgroundColor(0xFF15161E);
        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                ready = true;
                while (!pending.isEmpty()) {
                    evaluateJavascript(pending.poll(), null);
                }
            }
        });
        loadDataWithBaseURL("https://localhost/", HTML, "text/html", "utf-8", null);
    }

    private void js(String script) {
        if (ready) {
            evaluateJavascript(script, null);
        } else {
            pending.add(script);
            while (pending.size() > 64) {
                pending.poll();
            }
        }
    }

    /** Moves the scooter marker and pans to it. {@code accuracyM} may be null. */
    public void setScooter(double lat, double lon, Double accuracyM) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return;
        }
        double acc = accuracyM == null ? 0 : accuracyM;
        js(String.format(Locale.ROOT, "window.setScooter(%.6f,%.6f,%.1f);", lat, lon, acc));
    }

    /** Places (or clears, with null) the phone's own position marker. */
    public void setMe(Double lat, Double lon) {
        if (lat == null || lon == null) {
            js("window.setMe(null,null);");
        } else {
            js(String.format(Locale.ROOT, "window.setMe(%.6f,%.6f);", lat, lon));
        }
    }

    /** Replaces the drawn trail. Each row is {@code [lat, lon]}. */
    public void setTrail(double[][] pts) {
        StringBuilder sb = new StringBuilder("window.setTrail([");
        if (pts != null) {
            for (int i = 0; i < pts.length; i++) {
                if (pts[i] == null || pts[i].length < 2) {
                    continue;
                }
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]", pts[i][0], pts[i][1]));
            }
        }
        sb.append("]);");
        js(sb.toString());
    }

    /** Draws one coloured route per ride. {@code json} = {@code [{color,pts}]}. */
    public void setRoutesJson(String json) {
        js("window.setRoutes(" + (json == null || json.isEmpty() ? "[]" : json) + ");");
    }

    /** Draws stop markers with dwell time. {@code json} = {@code [{lat,lon,ms,open}]}. */
    public void setStopsJson(String json) {
        js("window.setStops(" + (json == null || json.isEmpty() ? "[]" : json) + ");");
    }

    /** Toggles the live pulse ring on the scooter marker. */
    public void setLive(boolean on) {
        js("window.setLive(" + on + ");");
    }

    private static final String HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>"
            + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
            + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
            + "<style>html,body,#map{height:100%;margin:0;background:#15161E}"
            + ".leaflet-control-layers,.leaflet-bar,.leaflet-bar a{background:#1A1B26;color:#C8D2F0;border-color:#2C3047}"
            + "@keyframes pulse{0%{transform:scale(1);opacity:.7}100%{transform:scale(2.4);opacity:0}}"
            + ".pulse{animation:pulse 1.4s ease-out infinite}</style>"
            + "</head><body><div id='map'></div><script>"
            // State the functions can touch before the map exists. The map is
            // built on window.load, after Leaflet's script has run; until then
            // calls just remember the latest values and apply on init.
            + "var map=null,marker=null,me=null,accC=null,trail=null,live=false,first=true;"
            + "var routeG=null,stopG=null;"
            + "var want={pos:null,me:null,trail:null,routes:null,stops:null};"
            + "function g(l){return 'https://mt1.google.com/vt?lyrs='+l+'&x={x}&y={y}&z={z}';}"
            + "function icon(){return L.divIcon({className:'',iconSize:[34,34],iconAnchor:[17,17],html:"
            + "'<div style=\"position:relative;width:34px;height:34px\">'"
            + "+(live?'<div class=pulse style=\"position:absolute;inset:0;border-radius:50%;background:#7AA2F7\"></div>':'')"
            + "+'<div style=\"position:absolute;inset:3px;border-radius:50%;background:#7AA2F7;"
            + "border:2px solid #15161E;box-shadow:0 1px 4px rgba(0,0,0,.6);"
            + "display:flex;align-items:center;justify-content:center;font-size:16px;line-height:1\">🛵</div></div>'});}"
            + "function init(){"
            + "if(typeof L==='undefined'){document.getElementById('map').innerHTML="
            + "'<div style=\"color:#8C95B8;padding:16px;font-family:sans-serif\">Map library did not load. Check the internet connection.</div>';return;}"
            + "map=L.map('map',{zoomControl:true,attributionControl:false}).setView([20.5937,78.9629],5);"
            + "var osm=L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19});"
            + "var esriSat=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:19});"
            + "var esriLbl=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',{maxZoom:19});"
            + "var esriHyb=L.layerGroup([esriSat,esriLbl]);"
            + "var traffic=L.tileLayer(g('h@221097413,traffic'),{maxZoom:20,opacity:0.7});"
            + "esriSat.addTo(map);"
            + "L.control.layers({'Satellite (Esri)':esriSat,'Hybrid (Esri)':esriHyb,'Streets (OSM)':osm,"
            + "'Google Streets':L.tileLayer(g('m'),{maxZoom:20}),'Google Satellite':L.tileLayer(g('s'),{maxZoom:20}),"
            + "'Google Hybrid':L.tileLayer(g('y'),{maxZoom:20})},{'Live traffic':traffic},{collapsed:true}).addTo(map);"
            + "trail=L.polyline([],{color:'#7AA2F7',weight:4,opacity:0.9}).addTo(map);"
            + "routeG=L.layerGroup().addTo(map);stopG=L.layerGroup().addTo(map);"
            + "setTimeout(function(){map.invalidateSize();},200);"
            + "if(want.trail){window.setTrail(want.trail);}"
            + "if(want.routes){window.setRoutes(want.routes);}"
            + "if(want.stops){window.setStops(want.stops);}"
            + "if(want.me){window.setMe(want.me[0],want.me[1]);}"
            + "if(want.pos){window.setScooter(want.pos[0],want.pos[1],want.pos[2]);}}"
            + "window.setScooter=function(lat,lon,a){want.pos=[lat,lon,a];if(!map)return;var ll=[lat,lon];"
            + "if(!marker){marker=L.marker(ll,{icon:icon(),zIndexOffset:1000}).addTo(map);}else{marker.setLatLng(ll);}"
            + "if(accC){map.removeLayer(accC);accC=null;}"
            + "if(a&&a>0){accC=L.circle(ll,{radius:a,color:'#7AA2F7',weight:1,opacity:0.4,"
            + "fillColor:'#7AA2F7',fillOpacity:0.08}).addTo(map);}"
            + "if(first){map.setView(ll,16);first=false;}else{map.panTo(ll,{animate:true});}};"
            + "window.setMe=function(lat,lon){if(lat==null){want.me=null;if(me&&map){map.removeLayer(me);me=null;}return;}"
            + "want.me=[lat,lon];if(!map)return;var ll=[lat,lon];if(!me){me=L.circleMarker(ll,{radius:6,color:'#15161E',weight:2,"
            + "fillColor:'#E0AF68',fillOpacity:1}).addTo(map);}else{me.setLatLng(ll);}};"
            + "window.setTrail=function(arr){want.trail=arr;if(trail){trail.setLatLngs(arr||[]);}};"
            + "function fmtDur(ms){var m=Math.round(ms/60000);if(m<60)return m+'m';"
            + "var h=Math.floor(m/60);var mm=m%60;return h+'h'+(mm?' '+mm+'m':'');}"
            + "function flag(ll,col,txt){return L.marker(ll,{icon:L.divIcon({className:'',iconSize:[12,12],iconAnchor:[6,6],"
            + "html:'<div style=\"width:12px;height:12px;border-radius:50%;background:'+col+';border:2px solid #15161E\"></div>'})})"
            + ".bindTooltip(txt,{direction:'top'});}"
            + "window.setRoutes=function(arr){want.routes=arr;if(!routeG)return;routeG.clearLayers();"
            + "(arr||[]).forEach(function(seg){var pts=seg.pts||[];if(!pts.length)return;"
            + "L.polyline(pts,{color:seg.color,weight:4,opacity:0.9}).addTo(routeG);"
            + "flag(pts[0],'#9ECE6A','Ride start').addTo(routeG);"
            + "if(pts.length>1)flag(pts[pts.length-1],'#F7768E','Ride end').addTo(routeG);});};"
            + "window.setStops=function(arr){want.stops=arr;if(!stopG)return;stopG.clearLayers();"
            + "(arr||[]).forEach(function(s){var dur=fmtDur(s.ms);"
            + "var txt=(s.open?'Parked here · ':'Stopped · ')+dur;"
            + "var col=s.open?'#F7768E':'#E0AF68';"
            + "L.marker([s.lat,s.lon],{icon:L.divIcon({className:'',iconSize:[32,32],iconAnchor:[16,16],"
            + "html:'<div style=\"width:32px;height:32px;border-radius:50%;background:rgba(21,22,30,.85);"
            + "border:2px solid '+col+';display:flex;align-items:center;justify-content:center;"
            + "font-size:9px;color:'+col+';font-family:sans-serif;font-weight:700;line-height:1;text-align:center\">'"
            + "+dur+'</div>'})}).bindTooltip(txt,{direction:'top'}).addTo(stopG);});};"
            + "window.setLive=function(on){live=on;if(marker){marker.setIcon(icon());}};"
            + "if(document.readyState==='complete'){init();}else{window.addEventListener('load',init);}"
            + "</script></body></html>";
}
