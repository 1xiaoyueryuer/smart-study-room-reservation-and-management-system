export async function validateEvidenceFile(file, { urls = URL, ImageClass = Image } = {}) {
  if (!['image/png','image/jpeg'].includes(file.type)) throw new Error('照片仅支持PNG或JPEG，请重新选择')
  if (!file.size || file.size > 5 * 1024 * 1024) throw new Error('每张照片须大于0且不超过5MiB')
  const url = urls.createObjectURL(file)
  try {
    await new Promise((resolve,reject) => {
      const image = new ImageClass()
      image.onload = () => image.naturalWidth > 0 && image.naturalHeight > 0 && image.naturalWidth <= 10000 && image.naturalHeight <= 10000 && image.naturalWidth * image.naturalHeight <= 20000000 ? resolve() : reject(new Error('照片单边不能超过10000像素，总像素不能超过2000万'))
      image.onerror = () => reject(new Error('照片损坏或无法解码，请更换PNG/JPEG文件'))
      image.src = url
    })
  } finally { urls.revokeObjectURL(url) }
}
