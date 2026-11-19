package world.andromeda.topsync;

import java.util.*;
import java.util.regex.*;

record Frame(long generation, Map<Frame.Key,List<Frame.Row>> boards) {
    Frame { var copy=new LinkedHashMap<Key,List<Row>>();boards.forEach((k,v)->copy.put(k,List.copyOf(v)));boards=Map.copyOf(copy); }
    record Key(String board,String type) { }
    record Row(int position,UUID id,String name,double score,Map<String,String> fields) {
        Row { fields=Map.copyOf(fields); }
        String field(String field) { return fields.getOrDefault(field,"???"); }
    }
    record Token(Key key,int position,String field) { }
    private static final Pattern TOKENS=Pattern.compile("%javascript_ajlbfallback_\\{(ajlb_lb_[^{}%]+)}%|%(ajlb_lb_[^%]+)%");
    private static final Pattern PARTS=Pattern.compile("ajlb_lb_(.+)_(\\d+)_(alltime|hourly|daily|weekly|monthly|yearly)_(name|displayname|prefix|suffix|value|value_raw|value_formatted|value_time|position)",Pattern.CASE_INSENSITIVE);
    static Optional<Token> token(String raw) {
        Matcher m=PARTS.matcher(raw);if(!m.matches())return Optional.empty();
        return Optional.of(new Token(new Key(m.group(1),m.group(3).toUpperCase(Locale.ROOT)),Integer.parseInt(m.group(2)),m.group(4).toLowerCase(Locale.ROOT)));
    }
    static Set<Key> keys(List<String> lines) {
        Set<Key> result=new LinkedHashSet<>();
        for(String line:lines) { Matcher m=TOKENS.matcher(line);while(m.find())token(m.group(1)==null?m.group(2):m.group(1)).ifPresent(t->result.add(t.key)); }
        return result;
    }
    static boolean supported(List<String> lines) {
        for(String line:lines){Matcher m=TOKENS.matcher(line);while(m.find()){
            var t=token(m.group(1)==null?m.group(2):m.group(1));
            if(t.isEmpty() || t.get().position<1 || t.get().position>100)return false;
        }}return true;
    }
    List<String> render(List<String> template) {
        return template.stream().map(line->{
            Matcher m=TOKENS.matcher(line);StringBuilder out=new StringBuilder();
            while(m.find()) {
                var token=token(m.group(1)==null?m.group(2):m.group(1));
                if(token.isEmpty() || !boards.containsKey(token.get().key))continue;
                Token t=token.get();List<Row> rows=boards.get(t.key);
                String value=t.position>0 && t.position<=rows.size()?rows.get(t.position-1).field(t.field):"???";
                m.appendReplacement(out,Matcher.quoteReplacement(value));
            }
            m.appendTail(out);return out.toString();
        }).toList();
    }
    static List<Row> rank(List<Row> input,boolean reverse) {
        Comparator<Row> comparator=Comparator.comparingDouble(Row::score);
        if(!reverse)comparator=comparator.reversed();
        comparator=comparator.thenComparing(r->r.id.toString());
        return input.stream().sorted(comparator).toList();
    }
}
