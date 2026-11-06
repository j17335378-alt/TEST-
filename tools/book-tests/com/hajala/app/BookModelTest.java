package com.hajala.app;
import java.nio.file.*;
import java.util.*;
public class BookModelTest {
    static int pass=0, fail=0;
    static void check(String name, boolean ok){ if(ok){pass++; System.out.println("PASS  "+name);} else {fail++; System.out.println("FAIL  "+name);} }
    static BookModel.Block qa(BookModel m, int page, String q, String... a){ BookModel.Block b=BookModel.newBlock(BookModel.T_QA); b.text=q; b.lines.clear(); for(String x:a)b.lines.add(x); m.page(page).blocks.add(b); return b; }
    public static void main(String[] args) throws Exception {
        BookModel m=BookModel.parse("",StudioStore.BOOK_QA);
        check("new model has one page", BookModel.parse("",StudioStore.BOOK_QA).pages.size()==1);
        m.pages.add(new BookModel.Page());
        m.pages.add(new BookModel.Page());
        BookModel.Block q1=qa(m,0,"ما عاصمة مصر؟","القاهرة");
        BookModel.Block q2=qa(m,0,"سؤال ٢","");
        BookModel.Block q3=qa(m,1,"سؤال ٣","جواب");
        BookModel.Block q4=qa(m,1,"سؤال ٤","a","b");
        check("numbers sequential across pages", m.questionNumber(q1)==1&&m.questionNumber(q2)==2&&m.questionNumber(q3)==3&&m.questionNumber(q4)==4);
        BookModel.addAnswerLine(q1); BookModel.addAnswerLine(q1);
        check("extra answer lines don't change next question number", q1.lines.size()==3 && m.questionNumber(q2)==2);
        check("remove answer line keeps >=1", BookModel.removeAnswerLine(q1,2)&&BookModel.removeAnswerLine(q1,1)&&!BookModel.removeAnswerLine(q1,0)&&q1.lines.size()==1);
        check("empty answer allowed", !BookModel.hasAnswer(q2));
        // delete q2 -> renumber
        m.page(0).blocks.remove(q2);
        check("renumber after delete", m.questionNumber(q3)==2&&m.questionNumber(q4)==3&&m.questionCount()==3);
        // reorder
        check("move down", m.move(m.page(1),q3,1) && m.questionNumber(q4)==2 && m.questionNumber(q3)==3);
        check("move up", m.move(m.page(1),q3,-1) && m.questionNumber(q3)==2);
        check("move out of range refused", !m.move(m.page(1),q3,-1) && !m.move(m.page(1),q4,1));
        // image + page removal
        BookModel.Block img=BookModel.newBlock(BookModel.T_IMAGE); img.itemId="item123"; m.page(1).blocks.add(img);
        List<String> imgs=m.removePage(1);
        check("remove page returns its images", imgs.size()==1&&imgs.get(0).equals("item123")&&m.pages.size()==2);
        check("numbers after page delete", m.questionNumber(q1)==1&&m.questionCount()==1);
        m.removePage(1); List<String> r2=m.removePage(0);
        check("last page is emptied, not removed", m.pages.size()==1 && m.page(0).blocks.isEmpty());
        // persistence round trip with Arabic, quotes, newlines
        BookModel p=BookModel.parse("",StudioStore.BOOK_QA); p.quiz=true;
        BookModel.Block a=qa(p,0,"سؤال \"بين علامات\"\nسطر ثانٍ","ج\\1","ج2");
        BookModel.Block t=BookModel.newBlock(BookModel.T_TASK); t.text="مهمة"; t.done=true; p.page(0).blocks.add(t);
        p.addPageAfter(0);
        String json=p.toJson();
        Path f=Files.createTempFile("book",".json"); Files.write(f,json.getBytes("UTF-8"));
        BookModel L=BookModel.parse(new String(Files.readAllBytes(f),"UTF-8"),StudioStore.BOOK_QA);
        BookModel.Block la=L.page(0).blocks.get(0);
        check("round trip pages", L.pages.size()==2 && L.quiz);
        check("round trip text exact", la.text.equals(a.text) && la.lines.equals(a.lines) && la.id.equals(a.id));
        check("round trip task", L.page(0).blocks.get(1).done && L.taskCount()==1 && L.taskDone()==1);
        check("json stable", L.toJson().equals(json));
        // corrupt / partial data never crashes
        check("garbage -> fresh page", BookModel.parse("{not json",StudioStore.BOOK_FREE).pages.size()==1);
        check("truncated file -> fresh page", BookModel.parse(json.substring(0,json.length()/2),StudioStore.BOOK_QA).pages.size()>=1);
        check("qa with no lines gets one", BookModel.parse("{\"pages\":[{\"blocks\":[{\"t\":\"qa\"}]}]}",StudioStore.BOOK_QA).page(0).blocks.get(0).lines.size()==1);
        check("unknown block type -> text", BookModel.parse("{\"pages\":[{\"blocks\":[{\"t\":\"zzz\",\"x\":\"a\"}]}]}",StudioStore.BOOK_FREE).page(0).blocks.get(0).type.equals(BookModel.T_TEXT));
        // many pages
        BookModel big=BookModel.parse("",StudioStore.BOOK_FREE); for(int i=0;i<500;i++) big.addPageAfter(big.pages.size()-1);
        check("500 pages round trip", BookModel.parse(big.toJson(),StudioStore.BOOK_FREE).pages.size()==501);
        // page insertion order
        BookModel o=BookModel.parse("",StudioStore.BOOK_FREE); String first=o.page(0).id; int at=o.addPageAfter(0); o.addPageAfter(0);
        check("insert after current", at==1 && o.pages.size()==3 && o.pages.get(0).id.equals(first));
        System.out.println("\n"+pass+" passed, "+fail+" failed");
        System.exit(fail==0?0:1);
    }
}
