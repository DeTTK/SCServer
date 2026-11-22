import os
import queue
import sys
import threading
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

from szr import convert


def main():
    root = tk.Tk()
    root.title("SCServer · Экспорт области в Axiom")
    panel = ttk.Frame(root, padding=18)
    panel.pack(expand=True, fill="both")
    panel.columnconfigure(1, weight=1)
    candidates = [Path.cwd()/"Map", Path.cwd().parent/"Map"]
    folder = tk.StringVar(value=str(next((p for p in candidates if (p/"region").is_dir()), "")))
    ttk.Label(panel, text="Сохранённая карта").grid(row=0,column=0,sticky="w")
    ttk.Entry(panel,textvariable=folder,width=42).grid(row=0,column=1,sticky="ew",padx=8)
    def choose():
        p=filedialog.askdirectory(parent=root,title="Выберите карту с region и registry.json")
        if p: folder.set(p)
    ttk.Button(panel,text="Обзор…",command=choose).grid(row=0,column=2)
    points = sys.argv[1:7] if len(sys.argv)==7 else ["0","64","0"]*2
    coordinates=[]
    for i,label in enumerate(["Первый угол","Второй угол"]):
        ttk.Label(panel,text=label).grid(row=i+1,column=0,sticky="w",pady=6)
        line=ttk.Frame(panel);line.grid(row=i+1,column=1,columnspan=2,sticky="w")
        for j,axis in enumerate("XYZ"):
            value=tk.StringVar(value=points[i*3+j]);coordinates.append(value)
            ttk.Label(line,text=axis).pack(side="left",padx=(8,2))
            ttk.Entry(line,textvariable=value,width=8).pack(side="left")
    status=tk.StringVar(value="F7 и F8 отмечают положение игрока. Здесь можно уточнить обе точки.")
    ttk.Label(panel,textvariable=status,wraplength=480).grid(row=3,column=0,columnspan=3,sticky="w",pady=10)
    results=queue.Queue()
    def poll():
        try: ok, text=results.get_nowait()
        except queue.Empty:
            root.after(100,poll);return
        button.state(["!disabled"]);status.set(text)
        if not ok: messagebox.showerror("Экспорт области",text,parent=root)
    def run():
        try:
            coords=[int(v.get()) for v in coordinates]; source=Path(folder.get())
            if not (source/"region").is_dir() or not (source/"registry.json").is_file(): raise ValueError("Выберите папку карты с region и registry.json.")
        except ValueError as err:
            messagebox.showerror("Область",str(err),parent=root);return
        destination=filedialog.asksaveasfilename(parent=root,title="Сохранить схему Axiom",defaultextension=".schematic",filetypes=[("Схема Axiom","*.schematic")])
        if not destination:return
        button.state(["disabled"]);status.set("Чтение карты и преобразование выделения…")
        def work():
            try:
                total, skipped=convert(source,coords[:3],coords[3:],Path(destination))
                details="\n".join(f"{n} × {k}" for k,n in skipped.items())
                if details:
                    report=Path(destination+".report.txt");report.write_text(details,encoding="utf-8")
                results.put((True,f"Сохранено в {destination}. Объектов декора: {total}."+(" Подробности пропусков в .report.txt." if details else "")))
            except Exception as err: results.put((False,str(err)))
        threading.Thread(target=work,daemon=True).start();poll()
    button=ttk.Button(panel,text="Сохранить .schematic…",command=run)
    button.grid(row=4,column=0,columnspan=3,sticky="e")
    root.mainloop()


if __name__ == "__main__":
    if len(sys.argv)==10 and sys.argv[1]=="--convert":
        count, warnings=convert(Path(sys.argv[2]),tuple(map(int,sys.argv[3:6])),tuple(map(int,sys.argv[6:9])),Path(sys.argv[9]))
        if warnings: Path(sys.argv[9]+".report.txt").write_text("\n".join(f"{n} × {key}" for key,n in warnings.items()),encoding="utf8")
    else: main()
