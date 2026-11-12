import { useEffect, useState } from "react";
import {
  BookOpen,
  ChevronRight,
  Download,
  FolderOpen,
  Home,
  ImagePlus,
  Moon,
  NotebookPen,
  Plus,
  Settings,
  Sun,
  Trash2,
  UserRound,
} from "lucide-react";
import { cn } from "@/lib/cn";
import {
  addChapter,
  addStudioFiles,
  addWork,
  downloadProjectZip,
  fileCount,
  formatSize,
  initProject,
  removeStudioFile,
  removeWork,
  setDefaultPaper,
  setFontScale,
  setName,
  setTheme,
  useProject,
  type FontScale,
  type Work,
} from "@/lib/project-store";
import { NotebooksView, PaperPicker } from "@/components/notebooks";

type Tab = "home" | "library" | "notebooks" | "studio" | "me";

export function ArcanumApp() {
  const data = useProject();
  const [tab, setTab] = useState<Tab>("me");
  const [settings, setSettings] = useState(true);
  const [workId, setWorkId] = useState<string | null>(null);

  useEffect(() => {
    void initProject();
  }, []);

  const work = data.works.find((item) => item.id === workId) ?? null;

  function openTab(next: Tab) {
    setTab(next);
    setWorkId(null);
    if (next !== "me") setSettings(false);
  }

  return (
    <div className="min-h-dvh bg-bg text-fg">
      <div className="mx-auto flex min-h-dvh w-full max-w-lg flex-col">
        <header className="flex items-center gap-3 border-b border-border px-4 py-3">
          <img src="/emblem.webp" alt="" className="size-11 rounded-full border border-border object-cover" />
          <div className="min-w-0 flex-1">
            <p className="font-display text-sm tracking-widest text-accent">ARCANUM</p>
            <h1 className="truncate text-lg font-bold leading-tight">أركانوم</h1>
          </div>
        </header>

        <main className="flex-1 px-4 py-4">
          {!data.ready ? (
            <p className="text-muted">جارٍ فتح ملفات المشروع…</p>
          ) : tab === "home" ? (
            <HomeView
              name={data.name}
              works={data.works.length}
              files={data.files.length}
              onLibrary={() => openTab("library")}
              onStudio={() => openTab("studio")}
              onSettings={() => {
                setTab("me");
                setSettings(true);
              }}
            />
          ) : tab === "library" && work ? (
            <WorkView work={work} onBack={() => setWorkId(null)} />
          ) : tab === "library" ? (
            <LibraryView works={data.works} onOpen={setWorkId} />
          ) : tab === "notebooks" ? (
            <NotebooksView />
          ) : tab === "studio" ? (
            <StudioView />
          ) : settings ? (
            <SettingsView onBack={() => setSettings(false)} />
          ) : (
            <ProfileView name={data.name} onSettings={() => setSettings(true)} />
          )}
        </main>

        {data.status ? (
          <p className="mx-4 mb-3 rounded-2xl border border-border bg-surface px-4 py-3 text-sm text-muted">
            {data.status}
          </p>
        ) : null}

        <nav className="safe-bottom sticky bottom-0 grid grid-cols-5 border-t border-border bg-bg">
          <TabButton active={tab === "home"} label="الرئيسية" onClick={() => openTab("home")}>
            <Home />
          </TabButton>
          <TabButton active={tab === "library"} label="المكتبة" onClick={() => openTab("library")}>
            <BookOpen />
          </TabButton>
          <TabButton active={tab === "notebooks"} label="الدفاتر" onClick={() => openTab("notebooks")}>
            <NotebookPen />
          </TabButton>
          <TabButton active={tab === "studio"} label="الاستوديو" onClick={() => openTab("studio")}>
            <FolderOpen />
          </TabButton>
          <TabButton active={tab === "me"} label="ملفي" onClick={() => openTab("me")}>
            <UserRound />
          </TabButton>
        </nav>
      </div>
    </div>
  );
}

function TabButton({
  active,
  label,
  onClick,
  children,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex min-h-14 flex-col items-center justify-center gap-1 text-xs",
        active ? "text-accent" : "text-muted",
      )}
    >
      <span className="size-5 [&_svg]:size-5">{children}</span>
      {label}
    </button>
  );
}

function HomeView({
  name,
  works,
  files,
  onLibrary,
  onStudio,
  onSettings,
}: {
  name: string;
  works: number;
  files: number;
  onLibrary: () => void;
  onStudio: () => void;
  onSettings: () => void;
}) {
  return (
    <div className="space-y-4">
      <section className="overflow-hidden rounded-3xl border border-border bg-surface">
        <img src="/logo.webp" alt="" className="h-40 w-full object-cover" />
        <div className="space-y-1 px-4 py-4">
          <p className="text-sm text-muted">مرحبًا {name}</p>
          <h2 className="text-2xl font-bold">عالمك من المانغا والمانهوا</h2>
          <p className="text-sm leading-relaxed text-muted">
            {works} أعمال و{files} ملفات في الاستوديو. كل شيء يُحفظ على هذا الجهاز، ويمكن تنزيله من الإعدادات.
          </p>
        </div>
      </section>
      <button type="button" onClick={onLibrary} className="card-btn">
        <BookOpen />
        <span>
          <b>المكتبة</b>
          <small>الأعمال والفصول والصفحات</small>
        </span>
      </button>
      <button type="button" onClick={onStudio} className="card-btn">
        <FolderOpen />
        <span>
          <b>الاستوديو</b>
          <small>صور وملفات أضفتها بنفسك</small>
        </span>
      </button>
      <button type="button" onClick={onSettings} className="card-btn">
        <Download />
        <span>
          <b>تحميل ملفات المشروع</b>
          <small>من الإعدادات، في ملف مضغوط واحد</small>
        </span>
      </button>
    </div>
  );
}

function LibraryView({ works, onOpen }: { works: Work[]; onOpen: (id: string) => void }) {
  const [title, setTitle] = useState("");
  const [kind, setKind] = useState("مانهوا");
  const [openForm, setOpenForm] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-bold">المكتبة</h2>
        <button type="button" className="btn-line" onClick={() => setOpenForm((v) => !v)}>
          <Plus className="size-4" />
          عمل جديد
        </button>
      </div>
      {openForm ? (
        <form
          className="space-y-3 rounded-3xl border border-border bg-surface p-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (!title.trim()) return;
            void addWork(title, kind, "عام").then((id) => {
              setTitle("");
              setOpenForm(false);
              onOpen(id);
            });
          }}
        >
          <label className="block text-sm text-muted">
            عنوان العمل
            <input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              className="field mt-1"
              placeholder="مثال: ظل الياسمين"
            />
          </label>
          <div className="flex gap-2">
            {["مانغا", "مانهوا", "ونشوت"].map((item) => (
              <button
                key={item}
                type="button"
                onClick={() => setKind(item)}
                className={cn("chip", kind === item && "chip-on")}
              >
                {item}
              </button>
            ))}
          </div>
          <button type="submit" className="btn-fill w-full">
            إنشاء العمل
          </button>
        </form>
      ) : null}
      {works.length === 0 ? (
        <p className="rounded-3xl border border-border bg-surface px-4 py-8 text-center text-muted">
          لا توجد أعمال بعد. أنشئ عملًا ثم أضف فصوله.
        </p>
      ) : (
        <ul className="space-y-3">
          {works.map((work) => {
            const pages = work.chapters.reduce((sum, chapter) => sum + chapter.pageIds.length, 0);
            return (
              <li key={work.id}>
                <button type="button" onClick={() => onOpen(work.id)} className="card-btn w-full">
                  <BookOpen />
                  <span>
                    <b>{work.title}</b>
                    <small>
                      {work.kind} · {work.chapters.length} فصول · {pages} صفحة
                    </small>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function WorkView({ work, onBack }: { work: Work; onBack: () => void }) {
  const [title, setTitle] = useState("");
  const [confirm, setConfirm] = useState(false);

  return (
    <div className="space-y-4">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-1 text-sm text-accent">
        <ChevronRight className="size-4" />
        المكتبة
      </button>
      <div>
        <p className="text-sm text-muted">
          {work.kind} · {work.genre}
        </p>
        <h2 className="text-2xl font-bold">{work.title}</h2>
      </div>
      <form
        className="space-y-3 rounded-3xl border border-border bg-surface p-4"
        onSubmit={(event) => {
          event.preventDefault();
        }}
      >
        <label className="block text-sm text-muted">
          عنوان الفصل
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            className="field mt-1"
            placeholder={`الفصل ${work.chapters.length + 1}`}
          />
        </label>
        <label className="btn-fill w-full">
          <ImagePlus className="size-4" />
          إضافة فصل من صور
          <input
            type="file"
            accept="image/*"
            multiple
            className="sr-only"
            onChange={(event) => {
              const list = [...(event.target.files ?? [])];
              event.target.value = "";
              if (!list.length) return;
              void addChapter(work.id, title, list).then(() => setTitle(""));
            }}
          />
        </label>
      </form>
      {work.chapters.length === 0 ? (
        <p className="text-sm text-muted">لا توجد فصول بعد. الصور التي تضيفها تدخل في ملف المشروع المضغوط.</p>
      ) : (
        <ul className="space-y-2">
          {work.chapters.map((chapter) => (
            <li
              key={chapter.id}
              className="flex items-center justify-between rounded-2xl border border-border bg-surface px-4 py-3"
            >
              <span className="font-medium">{chapter.title}</span>
              <span className="text-sm text-muted">{chapter.pageIds.length} صفحة</span>
            </li>
          ))}
        </ul>
      )}
      {confirm ? (
        <button
          type="button"
          className="btn-line w-full"
          onClick={() => void removeWork(work.id).then(onBack)}
        >
          تأكيد حذف العمل وملفاته
        </button>
      ) : (
        <button type="button" className="btn-line w-full" onClick={() => setConfirm(true)}>
          <Trash2 className="size-4" />
          حذف العمل
        </button>
      )}
    </div>
  );
}

function StudioView() {
  const data = useProject();
  const [folder, setFolder] = useState("المعرض");

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-bold">الاستوديو</h2>
        <label className="btn-line">
          <Plus className="size-4" />
          إضافة ملفات
          <input
            type="file"
            multiple
            className="sr-only"
            onChange={(event) => {
              const list = [...(event.target.files ?? [])];
              event.target.value = "";
              if (list.length) void addStudioFiles(list, folder);
            }}
          />
        </label>
      </div>
      <div className="flex flex-wrap gap-2">
        {["المعرض", "الهوية", "الدراسة", "مسودات"].map((item) => (
          <button
            key={item}
            type="button"
            onClick={() => setFolder(item)}
            className={cn("chip", folder === item && "chip-on")}
          >
            {item}
          </button>
        ))}
      </div>
      <p className="text-sm text-muted">الملفات الجديدة تُحفظ في «{folder}» وتُضمَّن عند تحميل المشروع.</p>
      {data.files.length === 0 ? (
        <p className="rounded-3xl border border-border bg-surface px-4 py-8 text-center text-muted">
          لا توجد ملفات بعد.
        </p>
      ) : (
        <ul className="space-y-2">
          {data.files.map((file) => (
            <li
              key={file.id}
              className="flex items-center gap-3 rounded-2xl border border-border bg-surface px-3 py-3"
            >
              <FolderOpen className="size-5 shrink-0 text-accent" />
              <span className="min-w-0 flex-1">
                <b className="block truncate font-medium">{file.name}</b>
                <small className="text-muted">
                  {file.folder} · {formatSize(file.size)}
                </small>
              </span>
              <button
                type="button"
                aria-label={`حذف ${file.name}`}
                className="grid size-11 place-items-center rounded-full text-muted"
                onClick={() => void removeStudioFile(file.id)}
              >
                <Trash2 className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ProfileView({ name, onSettings }: { name: string; onSettings: () => void }) {
  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">ملفي</h2>
      <section className="rounded-3xl border border-border bg-surface p-4">
        <p className="text-sm text-muted">الاسم</p>
        <p className="text-lg font-bold">{name}</p>
      </section>
      <button type="button" onClick={onSettings} className="card-btn">
        <Settings />
        <span>
          <b>الإعدادات</b>
          <small>المظهر وتحميل كل ملفات المشروع</small>
        </span>
      </button>
    </div>
  );
}

function SettingsView({ onBack }: { onBack: () => void }) {
  const data = useProject();
  const counts = fileCount(data);
  const [busy, setBusy] = useState(false);

  return (
    <div className="space-y-4">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-1 text-sm text-accent">
        <ChevronRight className="size-4" />
        ملفي
      </button>
      <h2 className="text-2xl font-bold">الإعدادات</h2>

      <section className="space-y-3 rounded-3xl border border-border bg-surface p-4">
        <h3 className="font-bold">الاسم</h3>
        <input
          value={data.name}
          onChange={(event) => setName(event.target.value)}
          className="field"
          maxLength={40}
          aria-label="الاسم"
        />
      </section>

      <section className="space-y-3 rounded-3xl border border-border bg-surface p-4">
        <h3 className="font-bold">المظهر</h3>
        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            className={cn("chip justify-center py-3", data.theme === "dark" && "chip-on")}
            onClick={() => setTheme("dark")}
          >
            <Moon className="size-4" />
            داكن
          </button>
          <button
            type="button"
            className={cn("chip justify-center py-3", data.theme === "light" && "chip-on")}
            onClick={() => setTheme("light")}
          >
            <Sun className="size-4" />
            فاتح
          </button>
        </div>
      </section>

      <section className="space-y-3 rounded-3xl border border-border bg-surface p-4">
        <h3 className="font-bold">حجم الخط</h3>
        <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="حجم الخط">
          {(
            [
              ["sm", "صغير"],
              ["md", "متوسط"],
              ["lg", "كبير"],
            ] as [FontScale, string][]
          ).map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="radio"
              aria-checked={data.fontScale === id}
              className={cn("chip justify-center py-3", data.fontScale === id && "chip-on")}
              onClick={() => setFontScale(id)}
            >
              {label}
            </button>
          ))}
        </div>
      </section>

      <section className="space-y-3 rounded-3xl border border-border bg-surface p-4">
        <h3 className="font-bold">الدفاتر</h3>
        <p className="text-sm text-muted">نوع الورق الافتراضي عند إنشاء دفتر جديد.</p>
        <PaperPicker value={data.defaultPaper} onChange={setDefaultPaper} />
      </section>

      <section className="space-y-3 rounded-3xl border border-border bg-surface p-4">
        <h3 className="font-bold">ملفات المشروع</h3>
        <p className="text-sm leading-relaxed text-muted">
          يُجمَّع كل ما في المكتبة والاستوديو والملاحظات والإعدادات في ملف مضغوط واحد يمكنك فتحه على أي جهاز.
        </p>
        <ul className="grid grid-cols-2 gap-2 text-sm">
          <Count label="أعمال" value={counts.works} />
          <Count label="فصول" value={counts.chapters} />
          <Count label="صفحات" value={counts.pages} />
          <Count label="ملفات الاستوديو" value={counts.studio} />
          <Count label="دفاتر" value={counts.notes} />
          <Count label="ملفات المحتوى" value={counts.total} />
        </ul>
        <button
          type="button"
          className="btn-fill w-full"
          disabled={busy}
          onClick={() => {
            setBusy(true);
            void downloadProjectZip().finally(() => setBusy(false));
          }}
        >
          <Download className="size-5" />
          {busy ? "جارٍ التجهيز…" : "تحميل كل ملفات المشروع"}
        </button>
        {data.downloadHref && data.downloadName ? (
          <a href={data.downloadHref} download={data.downloadName} className="btn-line w-full">
            <Download className="size-4" />
            حفظ {data.downloadName}
          </a>
        ) : null}
      </section>
    </div>
  );
}

function Count({ label, value }: { label: string; value: number }) {
  return (
    <li className="rounded-2xl border border-border px-3 py-2">
      <b className="block text-lg leading-none">{value}</b>
      <span className="text-muted">{label}</span>
    </li>
  );
}
