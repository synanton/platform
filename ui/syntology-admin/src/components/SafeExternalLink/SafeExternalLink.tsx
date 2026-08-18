function assertSafeUrl(href: string): boolean {
  try {
    const url = new URL(href, window.location.origin);
    return url.protocol === "https:" || url.protocol === "http:" || url.protocol === "mailto:";
  } catch {
    return false;
  }
}

type Props = React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string };

export function SafeExternalLink({ href, children, ...rest }: Props) {
  if (!assertSafeUrl(href)) {
    return <span>{children} (blocked)</span>;
  }
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
      {children}
    </a>
  );
}
