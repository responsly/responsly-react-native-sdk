import TestRenderer, { act } from 'react-test-renderer';
import { useResponslyScreen } from '../index';
import NativeModule from '../NativeResponslySdk';

jest.mock('../NativeResponslySdk');

const native = NativeModule as unknown as Record<string, jest.Mock>;

function Screen({ name }: { name: string }) {
  useResponslyScreen(name);
  return null;
}

describe('useResponslyScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('reports the screen when the component mounts', () => {
    let renderer!: TestRenderer.ReactTestRenderer;
    act(() => {
      renderer = TestRenderer.create(<Screen name="Checkout" />);
    });
    expect(native.screen).toHaveBeenCalledTimes(1);
    expect(native.screen).toHaveBeenCalledWith('Checkout');
    act(() => renderer.unmount());
  });

  it('reports again only when the name changes', () => {
    let renderer!: TestRenderer.ReactTestRenderer;
    act(() => {
      renderer = TestRenderer.create(<Screen name="Checkout" />);
    });

    act(() => renderer.update(<Screen name="Checkout" />));
    expect(native.screen).toHaveBeenCalledTimes(1);

    act(() => renderer.update(<Screen name="Cart" />));
    expect(native.screen).toHaveBeenCalledTimes(2);
    expect(native.screen).toHaveBeenLastCalledWith('Cart');

    act(() => renderer.update(<Screen name="Checkout" />));
    expect(native.screen).toHaveBeenCalledTimes(3);
    expect(native.screen).toHaveBeenLastCalledWith('Checkout');

    act(() => renderer.unmount());
    expect(native.screen).toHaveBeenCalledTimes(3);
  });

  it('works with a path as the name (Expo Router)', () => {
    let renderer!: TestRenderer.ReactTestRenderer;
    act(() => {
      renderer = TestRenderer.create(<Screen name="/checkout" />);
    });
    expect(native.screen).toHaveBeenCalledWith('/checkout');
    act(() => renderer.unmount());
  });

  it('skips an empty name', () => {
    let renderer!: TestRenderer.ReactTestRenderer;
    act(() => {
      renderer = TestRenderer.create(<Screen name="" />);
    });
    expect(native.screen).not.toHaveBeenCalled();
    act(() => renderer.unmount());
  });
});
