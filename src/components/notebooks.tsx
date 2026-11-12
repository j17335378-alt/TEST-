import { useEffect, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  FilePlus2,
  NotebookPen,
  Plus,
  Search,
  Trash2,
} from "lucide-react";
import { cn } from "@/lib/cn";
import {
  NOTE_COLORS,
  addNote,
  addNotePage,
  removeNote,
  removeNotePage,
  renameNote,
  setNotePage,
  styleNote,
  useProject,
  type Note,
  type PaperName,
} from "@/lib/project-store";

export const PAPERS: { id: PaperName; label: string }[] = [
  { id: "ruled", label: "مسطّر" },
  { id: "grid", label: "مربعات" },
  { id: "dots", label: "نقاط" },
  { id: "plain", label: "سادة" },
];

function formatDate(at: number) {
  try {
    return new Date(at).toLocaleDateString("ar", { day: "numeric", month: "long" });
  } catch {
    return "";
  }
}

export function PaperPicker({
  value,
  onChange,
}: {
  value: PaperName;
  onChange: (paper: PaperName) => void;
}) {
  return (
    <div className="grid grid-cols-4 gap-2" role="radiogroup" aria-label="نوع الورق">
      {PAPERS.map((paper) => (
        <button
          key={paper.id}
          type="button"
          role="radio"
          aria-checked={value === paper.id}
          onClick={() => onChange(paper.id)}
          className={cn("paper-swatch", value === paper.id && "paper-swatch-on")}
        >
          <span className={cn("paper-sample", `paper-${paper.id}`)} />
          {paper.label}
        </button>
      ))}
    </div>
  );
}

function ColorPicker({ value, onChange }: { value: string; onChange: (color: string) => void }) {
  return (
    <div className="flex gap-3" role="radiogroup" aria-label="لون الغلاف">
      {NOTE_COLORS.map((color) => (
        <button
          key={color}
          type="button"
          role="radio"
          aria-checked={value === color}
          aria-label={`لون ${color}`}
          onClick={() => onChange(color)}
          className={cn("color-dot", value === color && "color-dot-on")}
          style={{ background: color }}
        />
      ))}
    </div>
  );
}

export function NotebooksView() {
  const data = useProject();
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState("");
  const [paper, setPaper] = useState<PaperName>(data.defaultPaper);
  const [color, setColor] = useState<string>(NOTE_COLORS[0]);
  const [query, setQuery] = useState("");

  const open = data.notes.find((note) => note.id === openId) ?? null;
  if (open) return <NotebookView note={open} onBack={() => setOpenId(null)} />;

  const needle = query.trim().toLowerCase();
  const shown = needle
    ? data.notes.filter(
        (note) =>
          note.title.toLowerCase().includes(needle) ||
          note.pages.some((page) => page.toLowerCase().includes(needle)),
      )
    : data.notes;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-bold">الدفاتر</h2>
        <button
          type="button"
          className="btn-line"
          onClick={() => {
            setPaper(data.defaultPaper);
            setCreating((v) => !v);
          }}
        >
          <Plus className="size-4" />
          دفتر جديد
        </button>
      </div>

      {creating ? (
        <form
          className="space-y-4 rounded-3xl border border-border bg-surface p-4"
          onSubmit={(event) => {
            event.preventDefault();
            const id = addNote(title, paper, color);
            setTitle("");
            setCreating(false);
            setOpenId(id);
          }}
        >
          <label className="block text-sm text-muted">
            اسم الدفتر
            <input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              className="field mt-1"
              maxLength={80}
              placeholder="مثال: ملاحظات الفصل الثالث"
              autoFocus
            />
          </label>
          <div className="space-y-2">
            <p className="text-sm text-muted">نوع الورق</p>
            <PaperPicker value={paper} onChange={setPaper} />
          </div>
          <div className="space-y-2">
            <p className="text-sm text-muted">لون الغلاف</p>
            <ColorPicker value={color} onChange={setColor} />
          </div>
          <button type="submit" className="btn-fill w-full">
            إنشاء الدفتر
          </button>
        </form>
      ) : null}

      {data.notes.length > 3 ? (
        <label className="relative block">
          <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="field ps-10"
            placeholder="ابحث في الدفاتر"
            aria-label="ابحث في الدفاتر"
          />
        </label>
      ) : null}

      {data.notes.length === 0 ? (
        <p className="rounded-3xl border border-border bg-surface px-4 py-8 text-center text-muted">
          لا توجد دفاتر بعد. أنشئ دفترًا وابدأ الكتابة.
        </p>
      ) : shown.length === 0 ? (
        <p className="rounded-3xl border border-border bg-surface px-4 py-8 text-center text-muted">
          لا نتائج مطابقة.
        </p>
      ) : (
        <ul className="grid grid-cols-2 gap-3">
          {shown.map((note) => (
            <li key={note.id}>
              <button type="button" onClick={() => setOpenId(note.id)} className="book-cover">
                <span className="book-spine" style={{ background: note.color }} />
                <span className="book-body">
                  <NotebookPen className="size-5" style={{ color: note.color }} />
                  <b className="line-clamp-2 text-base leading-snug">{note.title}</b>
                  <small className="line-clamp-2 text-muted">
                    {note.pages.find((page) => page.trim())?.trim() || "دفتر فارغ"}
                  </small>
                  <small className="mt-auto text-muted">
                    {note.pages.length} صفحة · {formatDate(note.at)}
                  </small>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function NotebookView({ note, onBack }: { note: Note; onBack: () => void }) {
  const [index, setIndex] = useState(0);
  const [confirm, setConfirm] = useState<null | "page" | "book">(null);
  const [flip, setFlip] = useState(0);

  const last = note.pages.length - 1;
  const current = Math.min(index, last);

  useEffect(() => {
    if (index > last) setIndex(last);
  }, [index, last]);

  function go(next: number) {
    if (next < 0 || next > last) return;
    setIndex(next);
    setConfirm(null);
    setFlip((v) => v + 1);
  }

  return (
    <div className="space-y-4">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-1 text-sm text-accent">
        <ChevronRight className="size-4" />
        الدفاتر
      </button>

      <input
        value={note.title}
        onChange={(event) => renameNote(note.id, event.target.value)}
        className="w-full border-0 border-b border-border bg-transparent pb-2 text-2xl font-bold outline-none focus:border-accent"
        maxLength={80}
        aria-label="اسم الدفتر"
      />

      <div className="notebook" style={{ ["--cover" as string]: note.color }}>
        <div className="notebook-rings" aria-hidden="true">
          {Array.from({ length: 7 }, (_, i) => (
            <i key={i} />
          ))}
        </div>
        <div className="notebook-sheet">
          <div className="notebook-head">
            <span>{note.title}</span>
            <span>{formatDate(note.at)}</span>
          </div>
          <div key={flip} className={cn("paper-wrap page-in", `paper-${note.paper}`)}>
            <textarea
              value={note.pages[current] ?? ""}
              onChange={(event) => setNotePage(note.id, current, event.target.value)}
              className="paper-text"
              placeholder="اكتب هنا…"
              aria-label={`الصفحة ${current + 1}`}
              spellCheck={false}
            />
          </div>
          <div className="notebook-foot">
            <button
              type="button"
              className="page-nav"
              onClick={() => go(current - 1)}
              disabled={current <= 0}
              aria-label="الصفحة السابقة"
            >
              <ChevronRight className="size-5" />
            </button>
            <span className="text-sm font-medium" dir="ltr">
              {current + 1} / {note.pages.length}
            </span>
            <button
              type="button"
              className="page-nav"
              onClick={() => go(current + 1)}
              disabled={current >= last}
              aria-label="الصفحة التالية"
            >
              <ChevronLeft className="size-5" />
            </button>
          </div>
        </div>
      </div>

      {note.pages.length > 1 ? (
        <div className="flex flex-wrap justify-center gap-2" aria-label="الصفحات">
          {note.pages.map((_, i) => (
            <button
              key={i}
              type="button"
              onClick={() => go(i)}
              aria-label={`الصفحة ${i + 1}`}
              aria-current={i === current}
              className={cn("page-dot", i === current && "page-dot-on")}
            />
          ))}
        </div>
      ) : null}

      <div className="grid grid-cols-2 gap-2">
        <button
          type="button"
          className="btn-fill"
          onClick={() => {
            addNotePage(note.id);
            setIndex(note.pages.length);
            setFlip((v) => v + 1);
            setConfirm(null);
          }}
        >
          <FilePlus2 className="size-4" />
          صفحة جديدة
        </button>
        {confirm === "page" ? (
          <button
            type="button"
            className="btn-line"
            onClick={() => {
              removeNotePage(note.id, current);
              setConfirm(null);
            }}
          >
            تأكيد الحذف
          </button>
        ) : (
          <button type="button" className="btn-line" onClick={() => setConfirm("page")}>
            <Trash2 className="size-4" />
            حذف الصفحة
          </button>
        )}
      </div>

      <section className="space-y-3 rounded-3xl border border-border bg-surface p-4">
        <h3 className="font-bold">شكل الدفتر</h3>
        <PaperPicker value={note.paper} onChange={(paper) => styleNote(note.id, { paper })} />
        <ColorPicker value={note.color} onChange={(color) => styleNote(note.id, { color })} />
      </section>

      {confirm === "book" ? (
        <button
          type="button"
          className="btn-line w-full"
          onClick={() => {
            removeNote(note.id);
            onBack();
          }}
        >
          تأكيد حذف الدفتر بكل صفحاته
        </button>
      ) : (
        <button type="button" className="btn-line w-full" onClick={() => setConfirm("book")}>
          <Trash2 className="size-4" />
          حذف الدفتر
        </button>
      )}
    </div>
  );
}
