import { useSyncExternalStore } from "react";
import JSZip from "jszip";

export type ThemeName = "dark" | "light";

export type Chapter = {
  id: string;
  title: string;
  pageIds: string[];
};

export type Work = {
  id: string;
  title: string;
  kind: string;
  genre: string;
  chapters: Chapter[];
};

export type StudioFile = {
  id: string;
  name: string;
  mime: string;
  size: number;
  folder: string;
  at: number;
};

export type PaperName = "ruled" | "grid" | "dots" | "plain";
export type FontScale = "sm" | "md" | "lg";

export const NOTE_COLORS = ["#d8b265", "#8d649e", "#5f9ea0", "#c8707a", "#6b8e5a"] as const;

export type Note = {
  id: string;
  title: string;
  pages: string[];
  paper: PaperName;
  color: string;
  at: number;
};

export type Snapshot = {
  ready: boolean;
  theme: ThemeName;
  name: string;
  fontScale: FontScale;
  defaultPaper: PaperName;
  works: Work[];
  files: StudioFile[];
  notes: Note[];
  status: string | null;
  downloadHref: string | null;
  downloadName: string | null;
};

type Persisted = Omit<Snapshot, "ready" | "status" | "downloadHref" | "downloadName">;

const META_KEY = "arcanum-project-v1";
const DB_NAME = "arcanum-project";
const DB_STORE = "blobs";

const empty: Snapshot = {
  ready: false,
  theme: "dark",
  name: "قارئ أركانوم",
  fontScale: "md",
  defaultPaper: "ruled",
  works: [],
  files: [],
  notes: [],
  status: null,
  downloadHref: null,
  downloadName: null,
};

let snapshot: Snapshot = empty;
const listeners = new Set<() => void>();
let boot: Promise<void> | null = null;

function emit(next: Snapshot) {
  snapshot = next;
  listeners.forEach((fn) => fn());
}

function patch(partial: Partial<Snapshot>) {
  emit({ ...snapshot, ...partial });
}

function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function useProject() {
  return useSyncExternalStore(subscribe, () => snapshot, () => empty);
}

function applyLook(theme: ThemeName, fontScale: FontScale) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.fontscale = fontScale;
}

function persist() {
  const data: Persisted = {
    theme: snapshot.theme,
    name: snapshot.name,
    fontScale: snapshot.fontScale,
    defaultPaper: snapshot.defaultPaper,
    works: snapshot.works,
    files: snapshot.files,
    notes: snapshot.notes,
  };
  applyLook(data.theme, data.fontScale);
  try {
    localStorage.setItem(META_KEY, JSON.stringify(data));
  } catch {
    patch({ status: "تعذّر حفظ التغييرات: مساحة التخزين ممتلئة." });
  }
}

type LegacyNote = Partial<Note> & { id: string; title: string; body?: string };

function normalizeNotes(list: LegacyNote[] | undefined): Note[] {
  return (list ?? []).map((note) => ({
    id: note.id,
    title: note.title,
    pages: note.pages?.length ? note.pages : [note.body ?? ""],
    paper: note.paper ?? "ruled",
    color: note.color ?? NOTE_COLORS[0],
    at: note.at ?? Date.now(),
  }));
}

function openDb() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore(DB_STORE);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function putBlob(id: string, blob: Blob) {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(DB_STORE, "readwrite");
    tx.objectStore(DB_STORE).put(blob, id);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

async function getBlob(id: string) {
  const db = await openDb();
  const blob = await new Promise<Blob | undefined>((resolve, reject) => {
    const tx = db.transaction(DB_STORE, "readonly");
    const req = tx.objectStore(DB_STORE).get(id);
    req.onsuccess = () => resolve(req.result as Blob | undefined);
    req.onerror = () => reject(req.error);
  });
  db.close();
  return blob;
}

async function deleteBlob(id: string) {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(DB_STORE, "readwrite");
    tx.objectStore(DB_STORE).delete(id);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

function uid() {
  return crypto.randomUUID();
}

function safeName(value: string) {
  const cleaned = value.replace(/[\\/:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim();
  return (cleaned || "ملف").slice(0, 80);
}

function uniqueName(name: string, used: Set<string>) {
  const base = safeName(name);
  if (!used.has(base)) {
    used.add(base);
    return base;
  }
  const dot = base.lastIndexOf(".");
  const stem = dot > 0 ? base.slice(0, dot) : base;
  const ext = dot > 0 ? base.slice(dot) : "";
  let i = 2;
  let next = `${stem} ${i}${ext}`;
  while (used.has(next)) {
    i += 1;
    next = `${stem} ${i}${ext}`;
  }
  used.add(next);
  return next;
}

function extFor(mime: string, fallback: string) {
  if (mime.includes("png")) return "png";
  if (mime.includes("webp")) return "webp";
  if (mime.includes("gif")) return "gif";
  if (mime.includes("jpeg") || mime.includes("jpg")) return "jpg";
  if (mime.includes("pdf")) return "pdf";
  if (mime.includes("mp4")) return "mp4";
  if (mime.includes("text")) return "txt";
  return fallback;
}

async function drawPage(title: string, chapter: string, page: number, total: number, line: string) {
  const canvas = document.createElement("canvas");
  canvas.width = 360;
  canvas.height = 540;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("canvas");
  ctx.fillStyle = "#f4efe6";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.strokeStyle = "#1a1220";
  ctx.lineWidth = 18;
  ctx.strokeRect(28, 28, canvas.width - 56, canvas.height - 56);
  ctx.strokeStyle = "#d8b265";
  ctx.lineWidth = 3;
  ctx.strokeRect(48, 48, canvas.width - 96, canvas.height - 96);
  ctx.fillStyle = "#1a1220";
  ctx.textAlign = "center";
  ctx.font = "700 42px Tajawal, sans-serif";
  ctx.fillText(title, canvas.width / 2, 84);
  ctx.fillStyle = "#6d5878";
  ctx.font = "500 16px Tajawal, sans-serif";
  ctx.fillText(chapter, canvas.width / 2, 112);
  ctx.fillStyle = "#2a1d31";
  ctx.font = "500 16px Tajawal, sans-serif";
  const words = line.split(" ");
  let row = "";
  let y = 170;
  for (const word of words) {
    const trial = row ? `${row} ${word}` : word;
    if (ctx.measureText(trial).width > 280) {
      ctx.fillText(row, canvas.width / 2, y);
      row = word;
      y += 28;
    } else {
      row = trial;
    }
  }
  if (row) ctx.fillText(row, canvas.width / 2, y);
  ctx.fillStyle = "#8d649e";
  ctx.font = "700 26px Tajawal, sans-serif";
  ctx.fillText(`${page} / ${total}`, canvas.width / 2, 500);
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("png"))), "image/png");
  });
  return blob;
}

async function fetchAsset(url: string, name: string, mime: string, folder: string) {
  const res = await fetch(url);
  const blob = await res.blob();
  const id = uid();
  await putBlob(id, blob);
  return {
    id,
    name,
    mime: blob.type || mime,
    size: blob.size,
    folder,
    at: Date.now(),
  } satisfies StudioFile;
}

async function seed() {
  await document.fonts.load("700 22px Tajawal").catch(() => undefined);
  const pages = [
    {
      title: "ظل الياسمين",
      chapter: "الفصل الأول",
      line: "في زقاقٍ تفوح منه رائحة الياسمين، فتحت ليلى الباب الثقيل ووجدت رسالة بلا توقيع.",
    },
    {
      title: "ظل الياسمين",
      chapter: "الفصل الأول",
      line: "الرسالة كانت مختومة بشمع ذهبي، وعليها جملة واحدة: لا تثقي بالذي يبتسم أولًا.",
    },
    {
      title: "ظل الياسمين",
      chapter: "الفصل الأول",
      line: "أطفأت المصباح، وبقي ظلها على الجدار أطول مما ينبغي، كأنه ينتظرها أن تتكلم.",
    },
    {
      title: "ظل الياسمين",
      chapter: "الفصل الثاني",
      line: "عند الفجر خرجت إلى السوق، والمدينة كلها تتظاهر أنها لم ترَ شيئًا ليلة أمس.",
    },
    {
      title: "ظل الياسمين",
      chapter: "الفصل الثاني",
      line: "التاجر العجوز مدّ لها مفتاحًا صدئًا وقال: الاستوديو في الطابق الأخير، والملفات ما زالت هناك.",
    },
    {
      title: "نبض الليل",
      chapter: "الفصل الأول",
      line: "ركض سامي بين الأسطح والمطر يمحو أثره، وفي جيبه شريحة واحدة لا يجوز أن تصل إليهم.",
    },
    {
      title: "نبض الليل",
      chapter: "الفصل الأول",
      line: "توقف عند حافة السطح. المدينة تحت قدميه كانت مضاءة، والطريق الوحيد كان إلى الأسفل.",
    },
  ];

  const works: Work[] = [
    {
      id: uid(),
      title: "ظل الياسمين",
      kind: "مانهوا",
      genre: "رومانسي",
      chapters: [
        { id: uid(), title: "الفصل الأول", pageIds: [] },
        { id: uid(), title: "الفصل الثاني", pageIds: [] },
      ],
    },
    {
      id: uid(),
      title: "نبض الليل",
      kind: "مانغا",
      genre: "أكشن",
      chapters: [{ id: uid(), title: "الفصل الأول", pageIds: [] }],
    },
  ];

  for (const work of works) {
    for (const chapter of work.chapters) {
      const batch = pages.filter((p) => p.title === work.title && p.chapter === chapter.title);
      for (let i = 0; i < batch.length; i += 1) {
        const page = batch[i];
        const blob = await drawPage(work.title, chapter.title, i + 1, batch.length, page.line);
        const id = uid();
        await putBlob(id, blob);
        chapter.pageIds.push(id);
      }
    }
  }

  const emblem = await fetchAsset("/emblem.webp", "شعار-أركانوم.webp", "image/webp", "الهوية");
  const logo = await fetchAsset("/logo.webp", "غلاف-أركانوم.webp", "image/webp", "الهوية");

  const notes: Note[] = [
    {
      id: uid(),
      title: "ملاحظات التشريح",
      pages: [
        "عظام الطرف العلوي: العضد، الكعبرة، الزند، وعظام الرسغ.",
        "القلب أربع حجرات، والشريان الأبهر يخرج من البطين الأيسر.\nراجع الرسومات في مجلد الدراسة قبل الاختبار.",
      ],
      paper: "grid",
      color: NOTE_COLORS[2],
      at: Date.now(),
    },
    {
      id: uid(),
      title: "دفتر علم الأدوية",
      pages: [
        "البادئة brady تعني بطء، tachy تعني سرعة.\nلا تخلط بين الجرعة اليومية والجرعة القصوى.",
        "احتفظ بنسخة خارج التطبيق قبل أي تحديث.",
      ],
      paper: "ruled",
      color: NOTE_COLORS[0],
      at: Date.now() - 1000,
    },
  ];

  emit({
    ready: true,
    theme: "dark",
    name: "قارئ أركانوم",
    fontScale: "md",
    defaultPaper: "ruled",
    works,
    files: [emblem, logo],
    notes,
    status: null,
    downloadHref: null,
    downloadName: null,
  });
  persist();
}

export function initProject() {
  if (!boot) {
    boot = (async () => {
      const raw = localStorage.getItem(META_KEY);
      if (raw) {
        const data = JSON.parse(raw) as Omit<Persisted, "notes"> & { notes?: LegacyNote[] };
        const theme: ThemeName = data.theme === "light" ? "light" : "dark";
        const fontScale: FontScale = data.fontScale ?? "md";
        emit({
          ...empty,
          ...data,
          theme,
          fontScale,
          defaultPaper: data.defaultPaper ?? "ruled",
          works: data.works ?? [],
          files: data.files ?? [],
          notes: normalizeNotes(data.notes),
          ready: true,
        });
        applyLook(theme, fontScale);
        return;
      }
      await seed();
    })().catch((error: unknown) => {
      boot = null;
      patch({
        ready: true,
        status: error instanceof Error ? error.message : "تعذّر فتح ملفات المشروع",
        downloadHref: null,
        downloadName: null,
      });
    });
  }
  return boot;
}

export function setTheme(theme: ThemeName) {
  patch({ theme });
  persist();
}

export function setFontScale(fontScale: FontScale) {
  patch({ fontScale });
  persist();
}

export function setDefaultPaper(defaultPaper: PaperName) {
  patch({ defaultPaper });
  persist();
}

export function clearStatus() {
  patch({ status: null });
}

export function setName(name: string) {
  patch({ name: name.slice(0, 40) });
  persist();
}

export async function addStudioFiles(list: File[], folder: string) {
  const added: StudioFile[] = [];
  for (const file of list) {
    const id = uid();
    await putBlob(id, file);
    added.push({
      id,
      name: file.name || "ملف",
      mime: file.type || "application/octet-stream",
      size: file.size,
      folder,
      at: Date.now(),
    });
  }
  patch({ files: [...added, ...snapshot.files] });
  persist();
}

export async function removeStudioFile(id: string) {
  await deleteBlob(id);
  patch({ files: snapshot.files.filter((file) => file.id !== id) });
  persist();
}

export async function addWork(title: string, kind: string, genre: string) {
  const work: Work = {
    id: uid(),
    title: title.trim(),
    kind,
    genre: genre.trim() || "عام",
    chapters: [],
  };
  patch({ works: [work, ...snapshot.works] });
  persist();
  return work.id;
}

export async function addChapter(workId: string, title: string, images: File[]) {
  const pageIds: string[] = [];
  for (const image of images) {
    const id = uid();
    await putBlob(id, image);
    pageIds.push(id);
  }
  const chapter: Chapter = {
    id: uid(),
    title: title.trim() || `الفصل ${nextChapterNumber(workId)}`,
    pageIds,
  };
  patch({
    works: snapshot.works.map((work) =>
      work.id === workId ? { ...work, chapters: [...work.chapters, chapter] } : work,
    ),
  });
  persist();
}

function nextChapterNumber(workId: string) {
  const work = snapshot.works.find((item) => item.id === workId);
  return (work?.chapters.length ?? 0) + 1;
}

export async function removeWork(id: string) {
  const work = snapshot.works.find((item) => item.id === id);
  if (work) {
    for (const chapter of work.chapters) {
      for (const pageId of chapter.pageIds) await deleteBlob(pageId);
    }
  }
  patch({ works: snapshot.works.filter((item) => item.id !== id) });
  persist();
}

function updateNote(id: string, change: (note: Note) => Note) {
  patch({
    notes: snapshot.notes.map((note) => (note.id === id ? { ...change(note), at: Date.now() } : note)),
  });
  persist();
}

export function addNote(title: string, paper: PaperName, color: string) {
  const note: Note = {
    id: uid(),
    title: title.trim() || "دفتر جديد",
    pages: [""],
    paper,
    color,
    at: Date.now(),
  };
  patch({ notes: [note, ...snapshot.notes] });
  persist();
  return note.id;
}

export function renameNote(id: string, title: string) {
  updateNote(id, (note) => ({ ...note, title: title.slice(0, 80) }));
}

export function styleNote(id: string, style: Partial<Pick<Note, "paper" | "color">>) {
  updateNote(id, (note) => ({ ...note, ...style }));
}

export function setNotePage(id: string, index: number, text: string) {
  updateNote(id, (note) => ({
    ...note,
    pages: note.pages.map((page, i) => (i === index ? text : page)),
  }));
}

export function addNotePage(id: string) {
  updateNote(id, (note) => ({ ...note, pages: [...note.pages, ""] }));
}

export function removeNotePage(id: string, index: number) {
  updateNote(id, (note) => {
    const pages = note.pages.filter((_, i) => i !== index);
    return { ...note, pages: pages.length ? pages : [""] };
  });
}

export function removeNote(id: string) {
  patch({ notes: snapshot.notes.filter((note) => note.id !== id) });
  persist();
}

export function fileCount(data: Pick<Snapshot, "works" | "files" | "notes">) {
  const pages = data.works.reduce(
    (sum, work) => sum + work.chapters.reduce((inner, chapter) => inner + chapter.pageIds.length, 0),
    0,
  );
  return {
    works: data.works.length,
    chapters: data.works.reduce((sum, work) => sum + work.chapters.length, 0),
    pages,
    studio: data.files.length,
    notes: data.notes.length,
    total: pages + data.files.length + data.notes.length + 2,
  };
}

export function formatSize(bytes: number) {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} م.ب`;
  if (bytes >= 1024) return `${Math.max(1, Math.round(bytes / 1024))} ك.ب`;
  return `${bytes} بايت`;
}

export async function downloadProjectZip() {
  patch({ status: "جارٍ جمع ملفات المشروع…" });
  try {
    const zip = new JSZip();
    const root = zip.folder("أركانوم") ?? zip;
    const counts = fileCount(snapshot);
    root.file(
      "manifest.json",
      JSON.stringify(
        {
          app: "أركانوم",
          format: "project-files",
          createdAt: new Date().toISOString(),
          owner: snapshot.name,
          counts,
        },
        null,
        2,
      ),
    );
    root.file(
      "الإعدادات.json",
      JSON.stringify(
        {
          name: snapshot.name,
          theme: snapshot.theme === "dark" ? "داكن" : "فاتح",
          fontScale: snapshot.fontScale,
          defaultPaper: snapshot.defaultPaper,
        },
        null,
        2,
      ),
    );

    const library = root.folder("المكتبة");
    if (!library) throw new Error("library");
    const workNames = new Set<string>();
    for (const work of snapshot.works) {
      const folderName = uniqueName(work.title, workNames);
      const workDir = library.folder(folderName);
      if (!workDir) continue;
      workDir.file(
        "عن-العمل.json",
        JSON.stringify(
          {
            title: work.title,
            kind: work.kind,
            genre: work.genre,
            chapters: work.chapters.map((chapter) => ({
              title: chapter.title,
              pages: chapter.pageIds.length,
            })),
          },
          null,
          2,
        ),
      );
      const chapterNames = new Set<string>();
      for (const chapter of work.chapters) {
        const chapterDir = workDir.folder(uniqueName(chapter.title, chapterNames));
        if (!chapterDir) continue;
        for (let index = 0; index < chapter.pageIds.length; index += 1) {
          const blob = await getBlob(chapter.pageIds[index]);
          if (!blob) continue;
          const ext = extFor(blob.type, "png");
          chapterDir.file(`${String(index + 1).padStart(3, "0")}.${ext}`, blob);
        }
      }
    }

    const studio = root.folder("الاستوديو");
    if (!studio) throw new Error("studio");
    const byFolder = new Map<string, StudioFile[]>();
    for (const file of snapshot.files) {
      const list = byFolder.get(file.folder) ?? [];
      list.push(file);
      byFolder.set(file.folder, list);
    }
    for (const [folder, files] of byFolder) {
      const dir = studio.folder(safeName(folder));
      if (!dir) continue;
      const names = new Set<string>();
      for (const file of files) {
        const blob = await getBlob(file.id);
        if (!blob) continue;
        dir.file(uniqueName(file.name, names), blob);
      }
    }

    const notesDir = root.folder("الملاحظات");
    if (notesDir) {
      const names = new Set<string>();
      for (const note of snapshot.notes) {
        const body = note.pages
          .map((page, i) => (note.pages.length > 1 ? `--- صفحة ${i + 1} ---\n${page}` : page))
          .join("\n\n");
        notesDir.file(uniqueName(`${note.title}.txt`, names), body);
      }
    }

    root.file(
      "اقرأني.txt",
      [
        "هذا أرشيف ملفات مشروع أركانوم.",
        "المكتبة: الأعمال وفصولها وصفحاتها.",
        "الاستوديو: الصور والملفات التي أضفتها.",
        "الملاحظات: الدفاتر النصية.",
        "الإعدادات.json: الاسم والمظهر.",
      ].join("\n"),
    );

    patch({ status: "جارٍ ضغط الملفات…" });
    const blob = await zip.generateAsync({ type: "blob", compression: "DEFLATE" }, (meta) => {
      patch({ status: `جارٍ الضغط… ${Math.round(meta.percent)}%` });
    });
    const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-");
    const filename = `أركانوم-ملفات-المشروع-${stamp}.zip`;
    if (snapshot.downloadHref) URL.revokeObjectURL(snapshot.downloadHref);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    patch({
      status: "تم تجهيز الملف المضغوط.",
      downloadHref: url,
      downloadName: filename,
    });
  } catch {
    patch({ status: "تعذّر إنشاء الملف المضغوط. حاول مرة أخرى." });
  }
}
