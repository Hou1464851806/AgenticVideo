import { api } from "./api";
export interface Upload {
  id: string;
  status: string;
  part_size: number;
  total_parts: number;
  asset_id?: string;
}
export async function uploadFile(
  project: string,
  file: File,
  signal: AbortSignal,
  update: (progress: number, uploadId: string) => void,
): Promise<Upload> {
  const contentType =
    file.type || (/\.mov$/i.test(file.name) ? "video/quicktime" : "video/mp4");
  const key = crypto.randomUUID();
  let session: Upload | undefined;
  try {
    session = await api<Upload>(`/projects/${project}/uploads/init`, {
      method: "POST",
      key,
      body: {
        filename: file.name,
        file_size: file.size,
        content_type: contentType,
      },
    });
    update(0, session.id);
    const parts: { part_number: number; etag: string }[] = [];
    for (let number = 1; number <= session.total_parts; number++) {
      if (signal.aborted) throw new DOMException("上传已取消", "AbortError");
      const blob = file.slice(
        (number - 1) * session.part_size,
        Math.min(number * session.part_size, file.size),
      );
      let etag = "";
      for (let attempt = 0; attempt < 3; attempt++) {
        if (signal.aborted) throw new DOMException("上传已取消", "AbortError");
        try {
          const signed = await api<{
            parts: { part_number: number; url: string }[];
          }>(`/uploads/${session.id}/parts/presign`, {
            method: "POST",
            body: { part_numbers: [number] },
          });
          const response = await fetch(signed.parts[0]!.url, {
            method: "PUT",
            body: blob,
            signal,
          });
          if (!response.ok) throw new Error("分片上传失败");
          etag = response.headers.get("ETag") ?? "";
          if (!etag) throw new Error("存储未返回分片 ETag");
          break;
        } catch (error) {
          if (signal.aborted || attempt === 2) throw error;
          await new Promise((resolve) =>
            setTimeout(resolve, 250 * 2 ** attempt),
          );
        }
      }
      parts.push({ part_number: number, etag });
      update(
        Math.round(
          (Math.min(number * session.part_size, file.size) / file.size) * 100,
        ),
        session.id,
      );
    }
    if (signal.aborted) throw new DOMException("上传已取消", "AbortError");
    // Keep this exact manifest for safe completion retry after an ambiguous network response.
    try {
      return await api(`/uploads/${session.id}/complete`, {
        method: "POST",
        body: { parts },
        replaySafe: true,
      });
    } catch (error) {
      if (error instanceof TypeError)
        return await api(`/uploads/${session.id}/complete`, {
          method: "POST",
          body: { parts },
          replaySafe: true,
        });
      throw error;
    }
  } catch (error) {
    if (signal.aborted && session)
      await api(`/uploads/${session.id}/abort`, {
        method: "POST",
        body: {},
      }).catch(() => undefined);
    throw error;
  }
}
